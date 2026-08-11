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
     * 그룹 진입 데드라인 검사가 <b>데드라인을 넘겨서까지 실행되는</b> 호출을 최대 1회로 묶어 그룹 수 비례는
     * 없앴지만, <b>그 1회의 길이는 여전히 무한</b>이다. 즉 DB가 무응답이면 {@code stop()}에
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
     * 이 구현은 {@code ContextClosedEvent}에서 내부 executor를 바로 shutdown한다. 즉
     * <b>모든 Lifecycle stop보다 먼저</b> 주기 실행이 끊긴다. 종료 시 drain은 스케줄러가
     * 아니라 이 훅이 책임진다.
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
     * <p>주기(fixedRate)는 목표 처리량과 Bulk Lua 실행 시간에 따라 조정.
     * 예: 초당 5000건 목표 시 CHUNK_SIZE·MAX_DRAIN과 함께 실측 후 결정.
     */
    @Scheduled(fixedRate = 1000)
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
     * 최대 1회로 묶인다(데드라인 전에 시작한 호출은 개수 제한이 없다 — 총 경과는
     * 데드라인 + 넘긴 호출 1회의 길이). 그 1회의 <b>길이</b>는 여전히 무한이다 — 길이를 줄이는 건
     * {@code socketTimeout}의 몫이고, 그건 별도 과제다.
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
     * Sprint 5-E: 상수 반환 (임시). Sprint 6+: DB 조회 + Caffeine 캐싱.
     */
    private long getMaxCapacity(String queueId) {
        return queueRepository.findByQueueId(queueId)
                .map(Queue::getMaxCapacity)
                .orElseThrow(() -> new IllegalStateException(
                        "Queue not found during batch processing: " + queueId));
    }


}
