package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * */

@Component
public class BatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    /** 한 사이클에 Global Queue에서 drain할 최대 건수. */
    private static final int MAX_DRAIN = 5000;

    /** queue별 Bulk Lua 한 번에 처리할 최대 건수 (청크 크기). */
    private static final int CHUNK_SIZE = 500;

    private final RedisQueueEngine queueEngine;
    private final QueueRepository queueRepository;

    public BatchProcessor(RedisQueueEngine queueEngine, QueueRepository queueRepository) {
        this.queueEngine = queueEngine;
        this.queueRepository = queueRepository;
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
     */
    private void processQueueGroup(String queueId, List<PendingEnqueue> pendings) {
        long maxCapacity = getMaxCapacity(queueId);

        // 안쪽 루프: CHUNK_SIZE씩 나눠 처리
        for (int i = 0; i < pendings.size(); i += CHUNK_SIZE) {
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
