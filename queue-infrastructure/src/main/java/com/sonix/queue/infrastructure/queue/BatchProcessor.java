package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * 드레인의 {@code maxCapacity} 캐시 유지 시간(ms). <b>기본 30,000. 0이면 끈다.</b>
     *
     * <p><b>목적</b> — 틱 50회/s × 큐 N개 = 초당 50N SELECT를 없앤다.
     * 큐 20개 · 2,000 rps에서 p99 평균 73.95 → 40.02ms, 판 간 분산 60 → 0.9ms (2026-09-02 A/B).
     *
     * <p><b>왜 만료가 있나</b> — 용량은 불변이 아니다. 장애 런북이 직접
     * {@code UPDATE queues SET max_capacity=...}를 지시한다. 만료가 없으면 그 UPDATE가
     * 무동작이 되고 회복 수단이 전 인스턴스 재기동뿐이다.
     *
     * <p><b>왜 30초</b> — 근거는 비용이 아니라 장애 대응 반응 시간이다.
     * ⚠️ 비용은 {@code 큐 수 ÷ TTL}이라 큐 개수 상한이 정해지면 다시 잡아라.
     *
     * <p>🪤 캐시가 JVM 안에 있어 <b>인스턴스마다 만료 시점이 다르다</b> — UPDATE 후 30초 창 동안
     * 판정이 갈리고 그 안에 수렴한다.
     *
     * <p>실측표·기각한 대안은 {@code doc/perf/ENQUEUE_TUNING.md} §2.
     */
    private final long capacityCacheTtlMillis;

    /**
     * queueId → (용량, 만료 시각 — 단조 시계 기준). ⚠️ {@code status}는 <b>여기 없다.</b> 드레인이 쓰는 것은 용량뿐이고,
     * 정지 판정은 {@code QueueEngineService}가 요청마다 한다 — 그 경로는 캐시를 타지 않는다.
     * 그래서 이 캐시는 PAUSED 반영을 늦추지 않는다(2026-09-03 확정).
     *
     * <p>🪤 만료된 엔트리를 청소하는 주체가 없다. 삭제된 큐의 엔트리가 프로세스 수명 동안 남지만
     * 큐 수가 수천 단위라 무해하다. 큐가 수십만이 되면 크기 상한이 필요하다.
     * 미존재 큐는 {@code IllegalStateException}을 던져 캐시에 들어가지 않으므로,
     * 엔트리 수는 <b>실재하는 큐 수</b>로 묶인다.
     */
    private final Map<String, CachedCapacity> capacityByQueueId = new ConcurrentHashMap<>();

    private record CachedCapacity(long value, long expiresAtNanos) {}

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

    public BatchProcessor(RedisQueueEngine queueEngine, QueueRepository queueRepository,
                          @Value("${queue.enqueue.capacity-cache-ttl-ms:30000}") long capacityCacheTtlMillis) {
        this.queueEngine = queueEngine;
        this.queueRepository = queueRepository;
        this.capacityCacheTtlMillis = capacityCacheTtlMillis;
        log.info("enqueue drain: capacity-cache-ttl={}ms (0=off)", capacityCacheTtlMillis);
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
     * <p><b>이 주기가 enqueue 응답 지연을 그대로 만든다.</b> 요청은 다음 틱을 기다리므로
     * 평균 대기가 주기의 절반, 최악이 주기 전체다. {@code p99 ≈ 0.99 × 주기 + c}
     * ({@code c} = Lua + Kafka ack, 로컬 7~19ms).
     *
     * <p><b>1000ms → 20ms (2026-08-27, k6).</b> FRS 목표 부하 200 rps에서 p99 32.32ms로
     * 목표(&lt;50ms)를 충족하고 2.5배까지 견고하다.
     * ⚠️ 프로덕션은 {@code c}가 커진다 — 목표를 못 넘기면 10ms로 내려라(환경변수 한 줄).
     *
     * <p>🔴 <b>p99는 큐 수의 함수다</b> — 틱당 그룹마다 {@code getMaxCapacity} + {@code enqueue_bulk}가
     * 붙는다. "몇 RPS에서 몇 ms"는 큐 수 없이는 의미가 없다.
     *
     * <p>실측표·기각한 주기·측정 함정 4종은 {@code doc/perf/ENQUEUE_TUNING.md} §1.
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
     * {@code getMaxCapacity}는 <b>캐시 히트면 DB를 안 탄다</b>(큐당 <b>TTL당</b> 1회 미스).
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
     * <p>✅ <b>캐싱은 2026-09-02에 들어왔다</b>({@code capacityByQueueId} 필드 주석에 A/B 실측).
     * 옛 주석의 "남은 미착수는 캐싱뿐"·"캐시를 붙일 때 답해야 할 것"은 그 시점에 거짓이 됐다.
     *
     * <p>🔑 <b>그 옛 경고의 주소는 여기가 아니라 요청 경로였다.</b> 경고는 "{@code status}가
     * 가변이라 TTL만큼 PAUSED 반영이 늦으면 정지시킨 큐에 사람이 계속 들어온다"였는데,
     * <b>드레인은 {@code status}를 읽지 않는다</b> — 용량 하나만 쓴다. 정지 판정은
     * {@code QueueEngineService}가 요청마다 하고 그 경로는 캐시를 타지 않는다.
     * 그래서 그 경고는 <b>요청 경로를 캐시하려는 사람</b>이 읽어야 하며, 결론은 그쪽에 적었다
     * ({@code QueueEngineService.findQueueAndVerifyOwner}).
     */
    private long getMaxCapacity(String queueId) {
        if (capacityCacheTtlMillis <= 0) {
            return loadMaxCapacity(queueId);
        }
        // computeIfAbsent를 쓰지 않는다 — 매핑 함수가 DB를 치는데, 그 안에서 예외가 나면
        // 맵 락을 쥔 채 풀려나가고 미존재 큐마다 락 구간이 생긴다. 미스는 드물다(큐당 TTL 1회).
        // 🔑 nanoTime이다. 벽시계가 아니라 단조 시계를 써야 NTP 역행에 만료가 밀리지 않는다
        // (역행 폭만큼 UPDATE 반영이 늦어지고, 그건 장애 중에 겪을 일이 아니다).
        // 같은 클래스가 종료 데드라인에도 nanoTime을 쓴다 — 일관성이 우연이 아니다.
        long now = System.nanoTime();
        CachedCapacity cached = capacityByQueueId.get(queueId);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.value();
        }
        long loaded = loadMaxCapacity(queueId);
        capacityByQueueId.put(queueId,
                new CachedCapacity(loaded, now + capacityCacheTtlMillis * 1_000_000L));
        return loaded;
    }

    private long loadMaxCapacity(String queueId) {
        return queueRepository.findByQueueId(queueId)
                .map(Queue::getMaxCapacity)
                .orElseThrow(() -> new IllegalStateException(
                        "Queue not found during batch processing: " + queueId));
    }


}
