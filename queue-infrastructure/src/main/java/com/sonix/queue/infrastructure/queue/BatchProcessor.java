package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 *  Global Queue 배치 처리 Consumer.
 *
 * <p><b>SmartLifecycle인 이유(종료 시 유실 방지):</b> Global Queue는 프로세스 메모리에만
 * 있으므로, 여기 남은 채 프로세스가 내려가면 그 요청은 Redis에도 DB에도 흔적이 없다.
 * 3자 대조(HTTP↔Redis↔MySQL)로도 검출되지 않는 유실이다. 그래서 종료 시 마지막 drain을
 * 돌려 대기 중인 Future를 전부 완결시킨다({@link #stop()}).
 *
 * <p><b>이 보장의 범위:</b> {@link #stop()}은 Global Queue에 <b>남아 있는</b> 것만 본다.
 * SIGTERM 시점에 실행 중이던 {@code @Scheduled} 틱이 이미 가져간 건들은 그 틱이 처리하며,
 * 양쪽이 같은 {@code drainDeadlineNanos}를 공유해 동일한 상한을 받는다.
 * 근거와 전제는 {@link #stop()}·{@link #getPhase()} javadoc에 적었다.
 *
 * <p><b>운영 전제:</b> 이 종료 경로는 <b>LB deregistration이 SIGTERM보다 먼저</b> 일어나는 것을
 * 전제한다. {@code stop()}이 {@code markShuttingDown()}을 켜는 시점부터 웹 커넥터가 pause되기까지
 * 최대 ≈10초(DB 무응답 시 무한) 동안, 이 인스턴스는 <b>새 enqueue를 100% 503으로 거절하면서
 * 커넥션은 계속 받는다.</b> LB가 늦게 빼면 롤링 배포마다 인스턴스당 그 시간만큼 503 스파이크가 난다.
 * → 배포 런북 필요(후속 과제).
 * */

@Component
public class BatchProcessor implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    /** 한 사이클에 Global Queue에서 drain할 최대 건수. */
    private static final int MAX_DRAIN = 5000;

    /** queue별 Bulk Lua 한 번에 처리할 최대 건수 (청크 크기). */
    private static final int CHUNK_SIZE = 500;

    /**
     * 종료 시 마지막 drain에 허용하는 시간. <b>목표치이며 하드 상한이 아니다.</b>
     *
     * <p>데드라인은 그룹 진입과 청크 <b>사이</b>에서만 검사되므로, 검사를 통과해 이미 시작된
     * 호출 1회는 끝까지 기다린다. Redis만 놓고 보면
     * <pre>
     *   5s(이 값) + Redis commandTimeout 5s(RedisConfig.COMMAND_TIMEOUT) ≈ 10s
     * </pre>
     * 이다. Redis 쪽 시한이 없으면(Lettuce 기본 60s) 이 상한도 없다 —
     * {@code SmartLifecycle.stop()}은 동기라 {@code timeout-per-shutdown-phase}가
     * 바깥에서 끊어주지 못하기 때문이다. 두 값은 반드시 함께 본다.
     *
     * <p><b>⚠️ ≈10s는 MySQL이 응답한다는 전제 위에서만 성립한다.</b> 이 사이클에는
     * Redis 말고 DB 호출({@link #getMaxCapacity}, 캐시 없는 {@code findByQueueId})도 있는데,
     * JDBC URL에 {@code socketTimeout}이 설정돼 있지 않다(Connector/J 기본 0 = 무기한).
     * 그룹 진입 데드라인 검사가 <b>데드라인을 넘겨서까지 실행되는</b> 호출을 최대 2회로 묶어 그룹 수 비례는
     * 없앴지만, <b>그 길이는 여전히 무한</b>이다.
     * (2회인 이유와 상각 조건은 {@link #processQueueGroup} javadoc 참조 — §75 이중 라우팅 이후
     *  {@code routeForWrite}의 {@code redis_cluster_no} 조회가 하나 더 붙는다.) 즉 DB가 무응답이면 {@code stop()}에
     * 상한이 없다. 길이를 유한화하려면 {@code socketTimeout}이 필요하며, 그 값은 종료 경로
     * 밖 전 구간(특히 queue-batch의 대량 write)에 영향이 있어 부하 검증과 함께 별도로 다룬다
     * — <b>후속 과제</b>.
     *
     * <p>이 값이 enqueue 대기(RedisQueueEngine.MAX_WAIT_SECONDS 30s)보다 충분히 작아야
     * "drain이 먼저 끝나고 요청이 응답을 받는다"는 순서가 고정된다. 같으면 경계에서
     * 어느 쪽이 먼저 터질지 불확실해진다.
     */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 5_000L;

    private final RedisQueueEngine queueEngine;
    private final QueueRepository queueRepository;

    /**
     * SmartLifecycle 실행 여부. 인스턴스 로컬 상태이며 <b>서버마다 값이 달라도 무해</b>하다 —
     * 종료는 인스턴스별로 독립적으로 일어나고, 이 값은 자기 프로세스의 종료 훅을 부를지만
     * 결정한다(분산 상태가 아님).
     */
    private volatile boolean running = false;

    /**
     * 종료 drain의 데드라인({@code System.nanoTime()} 기준). {@link #stop()}에서만 설정되고
     * 평시에는 {@code null}(= 시한 없음)이다. 인스턴스 로컬 상태이며 <b>서버마다 값이 달라도
     * 무해</b>하다 — 자기 프로세스의 종료 drain에만 쓰인다.
     */
    private volatile Long drainDeadlineNanos = null;

    public BatchProcessor(RedisQueueEngine queueEngine, QueueRepository queueRepository) {
        this.queueEngine = queueEngine;
        this.queueRepository = queueRepository;
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    /**
     * 종료 훅의 실행 시점(phase).
     *
     * <p>웹 graceful shutdown 단계({@link WebServerGracefulShutdownLifecycle#SMART_LIFECYCLE_PHASE},
     * = {@code Integer.MAX_VALUE - 1024})보다 <b>1 큰</b> 값이다. Lifecycle은 종료 시
     * phase 내림차순으로 stop되므로, 이 훅은 <b>웹 계층이 in-flight 요청을 기다리기 시작하기
     * 직전</b>에 실행된다. 그래야 drain으로 완결시킨 Future의 응답을 아직 살아 있는 커넥터가
     * 실제로 써 보낼 수 있다.
     *
     * <p>반대로 phase를 낮게(예: 기본값 0) 두면 웹 graceful 대기가 <b>먼저</b> 시작되는데,
     * 그때 in-flight 요청들은 drain해 줄 주체가 없어 30초를 기다렸다 503이 된다.
     * 종료가 안전해지는 게 아니라 실패가 느려질 뿐이다.
     *
     * <p><b>@Scheduled에 기대면 안 되는 이유:</b> 이 프로젝트는 가상 스레드 전제
     * ({@code spring.threads.virtual.enabled=true})라 스케줄러가 {@code SimpleAsyncTaskScheduler}인데,
     * 이 구현은 {@code ContextClosedEvent}에서 내부 executor를 shutdown한다. 종료 시 drain은
     * 스케줄러가 아니라 이 훅이 책임진다.
     *
     * <p><b>⚠️ 다만 그 shutdown이 끊는 것은 "새 틱"뿐이다 — 이미 실행 중인 틱은 안 끊는다.</b>
     * 근거(Spring 원본 대조):
     * <ul>
     *   <li>{@code SimpleAsyncTaskScheduler}가 내부 {@code scheduledExecutor}에 넣는 작업은
     *       {@code () -> execute(task)}, 즉 <b>가상 스레드로 디스패치만</b> 한다. 본문은
     *       스케줄러 스레드 밖에서 돈다.</li>
     *   <li>{@code ContextClosedEvent}에서 호출하는 것은 {@code shutdown()}이지 {@code shutdownNow()}가
     *       아니며, 어느 쪽이든 이미 디스패치된 본문에는 영향이 없다.</li>
     *   <li>{@code ExecutorLifecycleDelegate}의 활성 작업 카운터는 디스패치만 세므로,
     *       스케줄러의 {@code stop(Runnable)}도 실행 중인 틱을 기다리지 않고 즉시 콜백한다.</li>
     * </ul>
     * 즉 <b>실행 중인 틱을 기다려주는 주체가 종료 경로에 하나도 없다.</b> 그래도 안전한 이유는
     * {@link #stop()} javadoc 참조 — 그 틱도 같은 {@code drainDeadlineNanos}에 묶이기 때문이다.
     */
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
    }

    /**
     * 종료 시 마지막 drain.
     *
     * <p>남은 요청을 전부 처리해 Future를 완결시킨다. 처리하지 못한 잔여분은 조용히 버리지 않고
     * <b>건수를 ERROR로 남긴 뒤</b> 예외로 완결시킨다(호출자는 30초를 기다리는 대신 즉시 503).
     *
     * <p><b>⚠️ "유실 0"은 이 메서드 단독의 보장이 아니다.</b> SIGTERM이 {@code @Scheduled} 틱
     * 중간에 도착하면, 그 틱이 이미 {@code poll}해 간 최대 {@code MAX_DRAIN}건은 Global Queue에서
     * 빠져나가 <b>이 메서드의 시야 밖</b>에 있다. {@code while (!globalQueue.isEmpty())}는 그것들을
     * 보지 못하고 즉시 반환할 수 있다.
     *
     * <p>그럼에도 유실되지 않는 근거는 다음 넷이다.
     * <ol>
     *   <li>{@code drainDeadlineNanos}는 이 싱글턴의 {@code volatile} 필드이고, 실행 중인 틱도
     *       {@code processQueueGroup}에서 <b>같은 필드</b>를 읽는다. 이 메서드가 데드라인을 세우는
     *       순간 그 틱도 함께 묶여, 틱의 상한도 <b>≈10s</b>(5s + 진행 중 커맨드 1회)다.</li>
     *   <li>그 건들은 전부 {@code future.get(30s)}에 매달린 in-flight HTTP 요청이므로,
     *       다음 phase의 웹 graceful 20s가 그들을 기다려준다. 10s &lt; 20s.</li>
     *   <li>이중 처리는 불가능하다. {@code ConcurrentLinkedQueue.poll()}이 원자적이라 한 항목은
     *       틱 또는 이 메서드 <b>둘 중 하나만</b> 가져간다.</li>
     *   <li>틱이 버린 건수도 {@code processQueueGroup}이 자기 ERROR 로그를 남기므로 계정이 유지된다.</li>
     * </ol>
     *
     * <p>따라서 <b>이 보장은 "틱이 공유 데드라인을 지킨다"는 전제 위에 있다.</b> 그 전제가 깨지는
     * 유일한 경우는 DB 무응답으로 단일 호출이 무한히 늘어나는 것인데, 이는 {@code stop()} 자신에게도
     * 동일하게 적용되는 별건({@code socketTimeout} 후속 과제)이다.
     * <b>실행 중 틱이 데드라인을 실제로 준수하는지는 소스 논증으로만 확인했고 테스트가 없다 — 후속 과제.</b>
     */
    @Override
    public void stop() {
        this.running = false;

        // 이 시점 이후 도착하는 요청은 drain해 줄 주체가 없다. 엔진에 알려 즉시 실패시킨다.
        queueEngine.markShuttingDown();

        this.drainDeadlineNanos = System.nanoTime() + SHUTDOWN_DRAIN_TIMEOUT_MS * 1_000_000L;
        Exception failure = null;

        while (!queueEngine.getGlobalQueue().isEmpty()) {
            if (drainDeadlineExceeded()) {
                failure = drainTimeout();
                break;
            }
            try {
                processBatches();
            } catch (Exception e) {
                // 도달 불가에 가까운 방어. 사이클 내부의 실패는 processQueueGroup·processChunk가
                // 전부 잡아 해당 그룹/청크 안에서 끝낸다. 그럼에도 예기치 못한 예외가 올라오면
                // 재시도해봐야 같은 결과이므로 중단한다(무한 루프 방지).
                failure = e;
                break;
            }
        }

        failRemainingOnShutdown(failure);
    }

    /** 종료 drain 데드라인 초과 여부. 평시(null)에는 항상 false. */
    private boolean drainDeadlineExceeded() {
        Long deadline = this.drainDeadlineNanos;
        return deadline != null && System.nanoTime() - deadline >= 0;
    }

    private static IllegalStateException drainTimeout() {
        return new IllegalStateException(
                "Shutdown drain timed out after " + SHUTDOWN_DRAIN_TIMEOUT_MS + "ms");
    }

    /**
     * 마지막 drain에서도 처리하지 못한 잔여분 정리.
     *
     * <p>유실 건수를 반드시 로그에 남긴다. 이 큐의 내용은 Redis에도 DB에도 없어서
     * 사후 대조로 복구할 근거가 로그밖에 없다.
     */
    private void failRemainingOnShutdown(Exception failure) {
        ConcurrentLinkedQueue<PendingEnqueue> globalQueue = queueEngine.getGlobalQueue();
        Exception cause = (failure != null) ? failure
                : new IllegalStateException("Shutdown drain incomplete");

        int lost = 0;
        PendingEnqueue pending;
        while ((pending = globalQueue.poll()) != null) {
            pending.completeExceptionally(cause);
            lost++;
        }

        if (lost > 0) {
            log.error("Shutdown drain incomplete: {} enqueue request(s) dropped (no Redis/DB trace)",
                    lost, cause);
        } else if (failure != null) {
            log.error("Shutdown drain aborted, but Global Queue was already empty", failure);
        }
    }

    /**
     * Global Queue 배치 처리 실행.
     *
     * <p><b>이 주기가 enqueue 응답 지연을 그대로 만든다.</b> 요청은 Global Queue에 들어가
     * 다음 틱을 기다리므로, 평균 대기가 주기의 절반이고 최악이 주기 전체다.
     *
     * <p><b>1000ms → 20ms (2026-08-27).</b> CLAUDE.md가 "원안 10ms 대비 100배 이탈"로 지목한
     * 그 상수다. k6로 손익 곡선을 얻어 정했다.
     *
     * <p><b>① 주기 스윕</b> (유입 100 RPS 고정, 큐 10개, 주기만 변수):
     * <pre>
     *   주기      p50        p95        p99       evalsha
     *   1000ms  514.07ms   965.09ms   1000ms      14,710
     *    200ms  119.08ms   208.67ms   211.23ms    16,151
     *     50ms   40.80ms    60.66ms    61.51ms    18,404
     *     30ms   28.77ms    38.78ms    39.78ms    18,799
     *     20ms   23.59ms    25.86ms    26.85ms    19,521   ← 채택
     *     10ms   12.77ms    23.29ms    24.78ms    19,465
     * </pre>
     *
     * <p><b>② 유입별</b> — 100 RPS 스윕만 보고 정하면 틀린다. 주기 20ms 기본값, <b>큐 40개</b>,
     * 초기화 <b>1회</b> 후 연속 측정(판 사이 토큰 누적 있음). 유입 순서를 뒤집어 두 번 쟀다:
     * <pre>
     *   유입       정순 p99   역순 p99   FRS p99 &lt; 50ms
     *    200 rps    32.32ms    29.21ms   ✓  ← FRS 목표 부하
     *    500 rps    30.77ms    31.73ms   ✓
     *  1,000 rps    75.13ms    37.07ms   ⚠️ 순서 의존 — 단정 불가
     *  2,000 rps   103.60ms    81.06ms   ✗  양쪽 다 초과
     * </pre>
     * <b>FRS §13의 목표 부하는 200 rps다.</b> 거기서 2.5배(500 rps)까지 견고하게 충족한다.
     *
     * <p>🪤 <b>순서를 뒤집어 재라. 안 그러면 워밍업을 부하로 착각한다.</b> 처음엔 정순만 재고
     * "5배부터 초과"라고 결론냈는데, 역순에서 1,000 rps가 <b>토큰이 2배 더 쌓인 상태로도</b>
     * 37.07ms로 빨랐다. 누적 부하가 아니라 <b>JIT 워밍업</b>이다 — 정순에선 가벼운 판 뒤라
     * 덜 데워졌다. 25초 워밍업으로는 고부하 경로가 안 데워진다. <b>그 결론은 철회했다.</b>
     *
     * <p>🔴 <b>p99는 큐 수의 함수다.</b> 2,000 RPS 고정, 큐 수만 변수(429·503 0%):
     * <pre>
     *   큐 10 → p99  64.42ms      큐 20 → p99 77.37ms      큐 43 → p99 130.50ms
     * </pre>
     * 🪤 이 표는 <b>같은 환경 안의 상대 비교로만</b> 읽어라 — 위 유입별 표와 환경이 달라
     * 절대값을 섞으면 안 된다. (초기화까지 한 판이 따로 있으나 그 판은 {@code enq_429}가
     * 39.7%/7.3% 섞여 p99를 신뢰할 수 없어 쓰지 않았다. 원인은 {@code plan=FREE} —
     * ENTERPRISE 승격이 캐시 TTL 안에 반영되기 전에 부하가 시작된 하니스 산물이다.)
     * 틱당 그룹 수가 늘면 그룹마다 {@code getMaxCapacity}(DB) + {@code enqueue_bulk}(Lua)가 붙는다.
     * <b>그래서 "2,000 RPS에서 몇 ms"라는 문장은 큐 수 없이는 의미가 없다.</b>
     * (위 두 표는 큐 수도 환경도 달라 직접 비교하지 마라. 각각 자기 조건 안에서만 읽어라.)
     *
     * <p>🪤 <b>버스트를 잴 때 주기를 고정하지 않으면 무엇을 쟀는지 알 수 없다.</b> 최초 버스트는
     * {@code burst.sh}가 앱을 재기동하지도 주기를 지정하지도 않아, 직전 스윕이 남긴 20ms 위에서
     * 돌고는 "30ms 고정"이라고 보고했다. lead가 실측 대조로 잡았다
     * ({@code burst_100} p99 25.76ms ≈ {@code sweep_20} 26.85ms ≠ {@code sweep_30} 39.78ms).
     * 지금 {@code burst.sh}는 {@code MS=} 를 필수로 요구하고 스스로 재기동한다.
     *
     * <p><b>숫자가 아니라 이 관계식을 옮겨라</b> — 위 표는 로컬(부하 도구가 서버와 같은 머신)이다.
     * <pre>
     *   p99 ≈ 0.99 × 주기 + c        c = Lua + Kafka ack. 로컬 실측 7~19ms
     *   evalsha_rate ≤ 유입 RPS      그룹핑이 항목 수보다 많은 Lua를 만들 수 없다
     * </pre>
     * 앞 식은 <b>큐 수·유입률과 무관</b>하다(틱 대기는 순전히 주기의 함수다). 뒤 식은
     * <b>구조에서 나오는 상한</b>이지 위 표가 근거가 아니다 — 저 {@code evalsha} 합계에는
     * 요청마다 도는 {@code token-bucket.lua}와 배치 잡이 섞여 있어(1000ms 판이 6,000요청에
     * 14,710회다) 건당 귀속에 쓸 수 없다. <b>주기별 증가분 비교로만 읽어라.</b>
     *
     * <p>🪤 <b>왜 50ms가 아닌가.</b> 20ms와 evalsha 차이가 6%뿐인데 100 RPS에서도 p99 61.5ms로
     *    이미 목표를 못 넘는다.
     * 🪤 <b>왜 10ms가 아닌가.</b> 20ms 대비 얻는 것에 비해 틱이 2배다. 비용이 포화해 이득이 없다
     *    (20ms 19,521 vs 10ms 19,465 — 차이 0.29%는 노이즈다).
     * ⚠️ <b>목표 부하(200 rps)에서 여유는 17.7ms지만 5배부터 넘는다</b>(위 표). {@code c}는
     *    프로덕션에서 네트워크가 붙어 더 커지고, 큐 수가 늘면 p99가 함께 오른다.
     *    되돌리기가 환경변수 한 줄이니 사전에 여유를 사지 않았다 —
     *    <b>프로덕션에서 목표를 못 넘기면 10ms로 내려라.</b>
     *
     * <p>🪤 <b>측정할 때 워밍업을 빼지 마라.</b> 첫 스윕이 재기동 직후 바로 재서 p99가 콜드 스타트에
     * 오염됐다 — 같은 조건에서 워밍업 후 <b>577ms → 61ms(9배)</b>로 갈렸고, 그 오염된 값으로
     * "구조적으로 달성 불가"라는 <b>틀린 판정</b>을 냈다. 절차는 {@code ~/queue-platform-it/sweep.sh}.
     *
     * <p>✅ <b>{@code max} 2.2~2.6s는 서버 사건이 아니다</b>(2026-08-27 해소). k6의
     * {@code http_req_duration} max가 같은 판의 {@code iteration_duration} max(30ms 판 51.88ms)를
     * <b>넘는다</b> — iteration이 요청을 포함하므로 산술적으로 불가능하다. 서버 히스토그램에도
     * 333,002건 중 1초 초과가 0건이다. <b>부하 도구/WSL2 측정 산물</b>이다.
     */
    @Scheduled(fixedRateString = "${queue.enqueue.drain-interval-ms:20}")
    public void processBatches() {
        // 1. Global Queue에서 최대 MAX_DRAIN 건 drain
        List<PendingEnqueue> drained = drainGlobalQueue();

        if (drained.isEmpty()) {
            return;
        }

        // 2. queueId별 groupBy (삽입 순서 유지)
        Map<String, List<PendingEnqueue>> grouped = groupByQueueId(drained);

        // 3. 바깥 루프: queue별
        grouped.forEach(this::processQueueGroup);
    }

    /**
     * Global Queue에서 최대 MAX_DRAIN 건 poll.
     */
    private List<PendingEnqueue> drainGlobalQueue() {
        ConcurrentLinkedQueue<PendingEnqueue> globalQueue = queueEngine.getGlobalQueue();
        List<PendingEnqueue> drained = new ArrayList<>();

        for (int i = 0; i < MAX_DRAIN; i++) {
            PendingEnqueue pending = globalQueue.poll();
            if (pending == null) {
                break;
            }
            drained.add(pending);
        }

        return drained;
    }

    /**
     * queueId별 groupBy (삽입 순서 유지를 위해 LinkedHashMap).
     */
    private Map<String, List<PendingEnqueue>> groupByQueueId(List<PendingEnqueue> drained) {
        Map<String, List<PendingEnqueue>> grouped = new LinkedHashMap<>();
        for (PendingEnqueue pending : drained) {
            grouped.computeIfAbsent(pending.getQueueId(), k -> new ArrayList<>()).add(pending);
        }
        return grouped;
    }

    /**
     * 특정 queue 그룹 처리 (안쪽 루프: 청크 분할).
     *
     * <p>종료 drain 중이라면 <b>청크마다</b> 데드라인을 확인한다. 사이클 바깥(=processBatches
     * 호출 사이)에서만 보면 사이클 하나가 통째로 시한을 넘길 수 있고(청크 수 × Redis 응답 시간),
     * {@code stop()}은 동기라 아무도 그걸 끊어주지 못한다. 넘긴 시점의 남은 청크는 실행하지
     * 않고 예외로 완결시킨다 — 매달리는 것보다 즉시 실패가 낫다.
     *
     * <p><b>그룹 진입 시에도 확인하는 이유:</b> 아래 {@link #getMaxCapacity}는 캐시 없는
     * MySQL 조회이고 JDBC {@code socketTimeout}이 미설정(Connector/J 기본 0 = 무기한)이라
     * <b>이 호출 자체에는 시한이 없다</b>. 검사가 청크 루프에만 있으면 그룹마다 이 시한 없는
     * 호출을 한 번씩 물어 {@code stop()} 경과가 <b>그룹 수에 선형 비례</b>한다
     * (실측: DB 3s 가정 · 그룹 3개 → 9,016ms). 여기서 끊으면 시한 없는 DB 호출은
     * 최대 <b>2회</b>로 묶인다(데드라인 전에 시작한 호출은 개수 제한이 없다 — 총 경과는
     * 데드라인 + 넘긴 호출들의 길이). 그 호출의 <b>길이</b>는 여전히 무한이다 — 길이를 줄이는 건
     * {@code socketTimeout}의 몫이고, 그건 별도 과제다.
     *
     * <p><b>왜 1회가 아니라 2회인가(§75 이중 라우팅 도입 후):</b> 이 그룹이 지나는 DB 호출은
     * {@link #getMaxCapacity} 하나가 아니다. 아래 {@code executeBulkLua}가 소유 클러스터를
     * 모를 때 {@code queues.redis_cluster_no}를 한 번 더 읽는다
     * ({@code RedisQueueEngine.routeForWrite}). 다만 그 조회는 <b>(WAS, queueId)당 평생 1회</b>다 —
     * 결과가 인스턴스 로컬 맵에 남아 같은 큐의 이후 사이클에는 0회로 상각된다. 반면
     * {@code getMaxCapacity}는 캐시가 없어 매 그룹마다 든다.
     */
    private void processQueueGroup(String queueId, List<PendingEnqueue> pendings) {
        if (drainDeadlineExceeded()) {
            // 이 건들은 Global Queue에서 이미 빠져나왔으므로 Redis에도 DB에도 흔적이 없다.
            // 건수를 남기지 않으면 사후 대조로도 복구할 근거가 사라진다.
            log.error("Shutdown drain deadline exceeded before capacity lookup: {} enqueue request(s) for queue {} dropped",
                    pendings.size(), queueId);
            failAllPending(pendings, drainTimeout());
            return;
        }

        long maxCapacity;
        try {
            maxCapacity = getMaxCapacity(queueId);
        } catch (Exception e) {
            // 이 그룹의 실패가 사이클 전체를 깨면, 이미 drain된 다른 그룹의 요청들이
            // 아무 결과도 받지 못한 채 버려진다(Global Queue에서 이미 빠져나왔으므로
            // 다음 사이클도 그들을 보지 못한다). 실패는 이 그룹 안에서 끝낸다.
            log.error("Failed to resolve capacity for queue {}: {}", queueId, e.getMessage(), e);
            failAllPending(pendings, e);
            return;
        }

        // 안쪽 루프: CHUNK_SIZE씩 나눠 처리
        for (int i = 0; i < pendings.size(); i += CHUNK_SIZE) {
            if (drainDeadlineExceeded()) {
                List<PendingEnqueue> remaining = pendings.subList(i, pendings.size());
                // 이 건들은 Global Queue에서 이미 빠져나왔으므로 Redis에도 DB에도 흔적이 없다.
                // 건수를 남기지 않으면 사후 대조로도 복구할 근거가 사라진다.
                log.error("Shutdown drain deadline exceeded: {} enqueue request(s) for queue {} dropped",
                        remaining.size(), queueId);
                failAllPending(remaining, drainTimeout());
                return;
            }

            int end = Math.min(i + CHUNK_SIZE, pendings.size());
            List<PendingEnqueue> chunk = pendings.subList(i, end);

            processChunk(queueId, chunk, maxCapacity);
        }
    }

    /**
     * 단일 청크 Bulk Lua 처리 및 Future 완료.
     */
    private void processChunk(String queueId, List<PendingEnqueue> chunk, long maxCapacity) {
        try {
            Instant issuedAt = Instant.now();

            List<Object> bulkResult = queueEngine.executeBulkLua(queueId, chunk, maxCapacity, issuedAt);
            List<EnqueueResult> results = queueEngine.parseBulkResult(bulkResult);
            completePending(chunk, results);
        } catch (Exception e) {
            log.error("Failed to process chunk for queue {}: {}", queueId, e.getMessage(), e);
            failAllPending(chunk, e);
        }
    }

    /**
     * 각 PendingEnqueue의 Future에 결과 설정.
     *
     * <p>결과는 identifier가 아니라 <b>위치(index)</b>로 매칭한다. 같은 identifier가
     * 한 청크에 여러 건 들어올 수 있고(중복 진입), 그 경우 하나만 OK이고 나머지는
     * EXISTS이므로 identifier로 매칭하면 서로 다른 결과가 뭉개진다.
     *
     * <p>위치 계약이 깨졌다면 아무도 complete하지 않고 청크 전체를 실패시킨다.
     * 일부만 결과를 받는 중간 상태를 만들지 않기 위함이다.
     */
    private void completePending(List<PendingEnqueue> chunk, List<EnqueueResult> results) {
        if (results.size() != chunk.size()) {
            log.error("Result size mismatch: expected {}, got {}", chunk.size(), results.size());
            failAllPending(chunk, new IllegalStateException(
                    "Result size mismatch: expected " + chunk.size() + ", got " + results.size()));
            return;
        }

        for (int i = 0; i < chunk.size(); i++) {
            String requested = chunk.get(i).getIdentifier();
            String returned = results.get(i).getIdentifier();
            if (!requested.equals(returned)) {
                log.error("Result order mismatch at index {}: requested={}, returned={}",
                        i, requested, returned);
                failAllPending(chunk, new IllegalStateException(
                        "Result order mismatch at index " + i));
                return;
            }
        }

        for (int i = 0; i < chunk.size(); i++) {
            chunk.get(i).complete(results.get(i));
        }
    }

    /**
     * 처리 실패 시 청크의 모든 PendingEnqueue에 예외 전파.
     */
    private void failAllPending(List<PendingEnqueue> chunk, Exception e) {
        for (PendingEnqueue pending : chunk) {
            pending.completeExceptionally(e);
        }
    }

    /**
     * Queue의 최대 용량 조회.
     *
     * <p>⚠️ 구 주석은 "Sprint 5-E: 상수 반환(임시)"이었으나 <b>이미 DB를 읽는다</b>
     * ({@code queueRepository.findByQueueId}). 남은 미착수는 <b>캐싱뿐</b>이다 —
     * 지금은 그룹마다 조회가 든다(같은 파일의 드레인 주석 참조).
     *
     * <p>캐시를 붙일 때 답해야 할 것: {@code Queue}는 {@code status}가 가변이라
     * TTL만큼 PAUSED 반영이 늦으면 <b>정지시킨 큐에 사람이 계속 들어온다.</b>
     * {@code ownerByQueueId}(불변이라 무해)와 사정이 다르다.
     */
    private long getMaxCapacity(String queueId) {
        return queueRepository.findByQueueId(queueId)
                .map(Queue::getMaxCapacity)
                .orElseThrow(() -> new IllegalStateException(
                        "Queue not found during batch processing: " + queueId));
    }


}
