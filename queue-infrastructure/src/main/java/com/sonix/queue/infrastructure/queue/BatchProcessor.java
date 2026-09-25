package com.sonix.queue.infrastructure.queue;

import java.time.Duration;
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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * 이유: Global Queue 배치 처리 Consumer. <b>SmartLifecycle 인 것은 종료 시 유실 방지다.</b>
 * 문제: 메모리에만 있어 남은 채 내려가면 <b>Redis·DB·Kafka 3자 대조로도 검출되지 않는 유실</b>이 된다.
 * 해결: 종료 시 마지막 drain 을 돌려 대기 중인 Future 를 전부 완결시킨다({@link #stop()}).
 * ⚠️ 운영 전제: <b>LB deregistration 이 SIGTERM 보다 먼저</b>여야 한다 — 늦으면 롤링 배포마다
 *    인스턴스당 ≈10초 동안 새 enqueue 를 100% 503 으로 거절하면서 커넥션은 계속 받는다.
 *
 * @author sonix
 */

@Component
public class BatchProcessor implements SmartLifecycle {
    /**
     * 이유: 구간별 소요의 버킷 경계 — 대시보드가 {@code histogram_quantile(_bucket)} 로 p95 를 뽑는다.
     * 문제: 경계를 안 주면 Micrometer 가 {@code _bucket} 을 아예 발행하지 않아 패널이 영구히 빈다(Timer 기본은 count/sum/max, 실측).
     * 해결: 실측 기준선(redis 0.16ms · mysql 1.34ms · 틱 20ms · kafka 18ms/콜드 404ms)을 덮는 경계를 준다(§4-1).
     * 🪤 {@code QueueEngineService.STAGE_SLO} 와 같은 값이어야 한다 — 버킷이 갈리면 집계가 깨진다.
     *
     * @author sonix
     */
    private static final Duration[] STAGE_SLO = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(20),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(12)};

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    /** 한 사이클에 Global Queue에서 drain할 최대 건수. */
    private static final int MAX_DRAIN = 5000;

    /** queue별 Bulk Lua 한 번에 처리할 최대 건수 (청크 크기). */
    private static final int CHUNK_SIZE = 500;

    /**
     * 이유: 종료 시 마지막 drain 에 허용하는 시간. <b>목표치이며 하드 상한이 아니다.</b>
     * 원인: 청크 <b>사이</b>에서만 검사돼 실질 상한이 <b>이 값 + Redis commandTimeout 5s ≈ 10s</b> 다.
     * 🪤 이 값은 enqueue 대기(30s)보다 충분히 작아야 "drain 이 먼저 끝난다"는 순서가 고정된다.
     */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 5_000L;

    private final RedisQueueEngine queueEngine;
    private final QueueRepository queueRepository;

    /**
     * 이유: 드레인의 {@code maxCapacity} 캐시 유지 시간(ms). <b>기본 30,000 · 0 이면 끈다.</b>
     * 해결: 초당 50N SELECT 를 없앤다 — p99 73.95 → 40.02ms, 분산 60 → 0.9ms(2026-09-02 A/B).
     * 🔴 <b>만료가 필요한 이유</b>: 용량은 불변이 아니다 — 장애 런북이 직접 {@code UPDATE queues} 를
     *    지시한다. 만료가 없으면 그 UPDATE 가 무동작이 되고 회복 수단이 전 인스턴스 재기동뿐이다.
     * 🪤 30초의 근거는 비용이 아니라 <b>장애 대응 반응 시간</b>이다. 비용은 {@code 큐 수 ÷ TTL} 이다.
     */
    private final long capacityCacheTtlMillis;

    /**
     * 이유: queueId → (용량, 만료 시각 — 단조 시계). ⚠️ {@code status} 는 <b>여기 없다.</b>
     * 원인: 드레인이 쓰는 것은 용량뿐이고 정지 판정은 요청마다 한다 —
     *       그래서 이 캐시는 <b>PAUSED 반영을 늦추지 않는다</b>(2026-09-03 확정).
     * 🪤 만료 엔트리를 청소하는 주체가 없다 — 미존재 큐는 예외를 던져 캐시에 안 들어가므로
     *    엔트리 수가 <b>실재하는 큐 수</b>로 묶인다. 큐가 수십만이 되면 상한이 필요하다.
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

    /** 드레인 1틱의 소요. 이 값이 drain-interval(20ms)을 넘으면 틱이 밀리기 시작한 것이다. */
    private final Timer drainTimer;
    /** 한 틱이 빼간 건수. MAX_DRAIN(5000)에 붙으면 유입이 배출을 앞선 것이다. */
    private final DistributionSummary drainBatchSize;
    /** 구간별 소요. tag = tick(큐 대기) · redis(Lua) · mysql(용량 조회). kafka는 API 스레드에서 잰다. */
    private final Timer tickWait, redisTimer, mysqlTimer;

    /** 테스트용 — 레지스트리 없이 만든다. 측정은 SimpleMeterRegistry가 흡수한다. */
    public BatchProcessor(RedisQueueEngine queueEngine, QueueRepository queueRepository,
                          long capacityCacheTtlMillis) {
        this(queueEngine, queueRepository, capacityCacheTtlMillis, null);
    }

    /**
     * 이유: enqueue 를 모아 한 Lua 로 보내는 드레인 루프.
     * 문제: 지금까지 "어디서 30ms 가 가는가"를 **손으로만** 쟀다 — 다음 달엔 아무도 모른다.
     * 해결: 계기판 넷을 코드에 심는다(길이·소요·배치크기·구간). 손으로 잰 숫자는 지식이고
     *       코드에 심은 숫자는 자산이다.
     * 🪤 MeterRegistry 가 없는 컨텍스트가 있어 ObjectProvider 로 받고 없으면 Simple 로 흡수한다.
     *
     * @author sonix
     */
    @Autowired
    public BatchProcessor(RedisQueueEngine queueEngine, QueueRepository queueRepository,
                          @Value("${queue.enqueue.capacity-cache-ttl-ms:30000}") long capacityCacheTtlMillis,
                          ObjectProvider<MeterRegistry> registries) {
        this.queueEngine = queueEngine;
        this.queueRepository = queueRepository;
        this.capacityCacheTtlMillis = capacityCacheTtlMillis;
        MeterRegistry reg = registries == null ? new SimpleMeterRegistry()
                : registries.getIfAvailable(SimpleMeterRegistry::new);
        this.drainTimer = Timer.builder("queue.drain.duration")
                .description("드레인 1틱 소요. drain-interval(20ms)을 넘으면 틱이 밀린다")
                .serviceLevelObjectives(STAGE_SLO).register(reg);
        this.drainBatchSize = DistributionSummary.builder("queue.drain.batch.size")
                .description("한 틱이 빼간 건수. MAX_DRAIN에 붙으면 유입이 배출을 앞섰다").register(reg);
        this.tickWait = Timer.builder("queue.stage.duration").tag("stage", "tick")
                .description("큐에 담긴 뒤 드레인까지 기다린 시간")
                .serviceLevelObjectives(STAGE_SLO).register(reg);
        this.redisTimer = Timer.builder("queue.stage.duration").tag("stage", "redis")
                .serviceLevelObjectives(STAGE_SLO).register(reg);
        this.mysqlTimer = Timer.builder("queue.stage.duration").tag("stage", "mysql")
                .serviceLevelObjectives(STAGE_SLO).register(reg);
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
     * 이유: 종료 훅의 실행 시점(phase). 웹 graceful shutdown 단계보다 <b>1 큰</b> 값이다.
     * 문제: phase 를 낮추면 웹 대기가 <b>먼저</b> 시작돼 in-flight 가 30초 뒤 503 이 된다(실패가 느려질 뿐).
     * 해결: phase 내림차순 stop 이라 이 훅이 <b>웹이 in-flight 를 기다리기 직전</b>에 돈다.
     * 🔴 {@code @Scheduled} 에 기대면 안 된다 — 스케줄러가 {@code ContextClosedEvent} 에서 닫히는데,
     *    끊기는 것은 <b>새 틱뿐이고 실행 중인 틱은 안 끊긴다</b>(근거는 {@link #stop()}).
     *
     * @author sonix
     */
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
    }

    /**
     * 이유: 종료 시 마지막 drain — 남은 요청의 Future 를 전부 완결시킨다.
     * 해결: 잔여분은 <b>건수를 ERROR 로 남긴 뒤</b> 예외로 완결시킨다(호출자는 즉시 503).
     * ⚠️ <b>"유실 0"은 이 메서드 단독의 보장이 아니다</b> — 실행 중인 틱이 poll 해 간 건은 시야 밖이다.
     *    안전한 근거: ①데드라인이 volatile 공유 ②웹 graceful 20s ③{@code poll()} 이 원자(이중 처리 없음).
     * 🪤 실행 중 틱이 데드라인을 지키는지는 <b>소스 논증으로만 확인했고 테스트가 없다</b> — 후속 과제.
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
     * 이유: 배치 처리 실행. <b>이 주기가 곧 enqueue 지연이다</b> — {@code p99 ≈ 0.99 × 주기 + c}.
     * 해결: 1000ms → <b>20ms</b>(2026-08-27, k6). 목표 부하 200rps 에서 p99 32.32ms 로 목표(&lt;50ms) 충족.
     * 🔴 <b>p99 는 큐 수의 함수다</b> — 틱당 그룹마다 용량 조회 + Lua 가 붙는다.
     *    "몇 RPS 에서 몇 ms"는 <b>큐 수 없이는 의미가 없다</b>. 실측표와 측정 함정 4종은 doc/perf/ENQUEUE_TUNING.md §1.
     * ⚠️ 프로덕션은 c 가 커진다 — 목표를 못 넘기면 10ms 로 내려라(환경변수 한 줄).
     *
     * @author sonix
     */
    @Scheduled(fixedRateString = "${queue.enqueue.drain-interval-ms:20}")
    public void processBatches() {
        // 1. Global Queue에서 최대 MAX_DRAIN 건 drain
        long tickStart = System.nanoTime();
        List<PendingEnqueue> drained = drainGlobalQueue();
        // 계기판: 몇 건을 빼갔고, 각자 얼마나 기다렸나. "틱 대기"가 지연의 정체였던 적이 있다(그래서 drain 주기를 1000→20ms 로 줄였다).
        // 🪤 빈 틱은 기록하지 않는다 — 20ms 주기라 초당 50건의 0이 분포를 덮어 백분위가 무의미해진다(실측).
        if (!drained.isEmpty()) {
            drainBatchSize.record(drained.size());
            for (PendingEnqueue p : drained) {
                tickWait.record(tickStart - p.getCreatedNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }

        if (drained.isEmpty()) {
            return;
        }

        // 2. queueId별 groupBy (삽입 순서 유지)
        Map<String, List<PendingEnqueue>> grouped = groupByQueueId(drained);

        // 3. 바깥 루프: queue별
        grouped.forEach(this::processQueueGroup);
        drainTimer.record(System.nanoTime() - tickStart, java.util.concurrent.TimeUnit.NANOSECONDS);
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
     * 이유: 큐 그룹 하나를 청크로 나눠 처리한다.
     * 문제: 사이클 바깥에서만 보면 사이클 하나가 통째로 시한을 넘기는데 아무도 끊어주지 못한다.
     * 해결: <b>청크마다 + 그룹 진입 시</b> 확인한다 — 진입 검사가 없으면 {@code stop()} 경과가 <b>그룹 수에 선형 비례</b>한다(실측: 그룹 3개 → 9,016ms). 남은 청크는 예외로 완결시킨다.
     * 🪤 DB 호출이 <b>2회</b>인 것은 §75 라우팅 때문이다 — 용량 조회(캐시 TTL당 1회)와
     *    {@code redis_cluster_no} 조회(WAS 프로세스 하나에서 queueId 당 1회)다.
     *
     * @author sonix
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
            long mysqlStart = System.nanoTime();
            maxCapacity = getMaxCapacity(queueId);
            mysqlTimer.record(System.nanoTime() - mysqlStart, java.util.concurrent.TimeUnit.NANOSECONDS);
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

            long redisStart = System.nanoTime();
            List<Object> bulkResult = queueEngine.executeBulkLua(queueId, chunk, maxCapacity, issuedAt);
            redisTimer.record(System.nanoTime() - redisStart, java.util.concurrent.TimeUnit.NANOSECONDS);
            List<EnqueueResult> results = queueEngine.parseBulkResult(bulkResult);
            completePending(chunk, results);
        } catch (Exception e) {
            log.error("Failed to process chunk for queue {}: {}", queueId, e.getMessage(), e);
            failAllPending(chunk, e);
        }
    }

    /**
     * 이유: 각 {@link PendingEnqueue} 의 Future 에 결과를 넣는다.
     * 문제: identifier 로 매칭하면 <b>결과가 뭉개진다</b> — 중복 진입 시 하나만 OK, 나머지는 EXISTS 다.
     * 해결: <b>위치(index)</b> 로 매칭한다.
     * 🪤 위치 계약이 깨지면 아무도 complete 하지 않고 청크 전체를 실패시킨다 —
     *    일부만 결과를 받는 중간 상태를 만들지 않기 위해서다.
     *
     * @author sonix
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
     * 이유: 큐의 최대 용량 조회. 캐시는 {@code capacityByQueueId} 가 들고 있다(2026-09-02 A/B).
     * 🔑 <b>드레인은 {@code status} 를 읽지 않는다</b>(용량만 쓴다) — "PAUSED 반영이 늦는다"는
     *    옛 경고의 주소는 여기가 아니라 <b>요청 경로</b>다.
     * 🪤 못 찾으면 예외를 던진다 — stale read 가 "옛 값"이 아니라 <b>그 틱 그룹 전체 실패</b>가 되는
     *    경로라, 이 조회를 replica 로 옮기면 안 된다(§4-3).
     *
     * @author sonix
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
