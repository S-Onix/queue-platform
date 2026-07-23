package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class QueueEngineService {
    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;

    public QueueEngineService(QueueRepository queueRepository, QueueEngine queueEngine, EnqueueEventPublisher eventPublisher) {
        this.queueEngine = queueEngine;
        this.queueRepository = queueRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 대기열 진입 처리.
     *
     * @param queueId 대기열 외부 식별자 (예: "q_xyz789")
     * @param identifier 사용자 식별자 (Tenant가 자유 지정)
     * @return 진입 결과 (OK 또는 EXISTS)
     * @throws BusinessException queue가 없거나(QUEUE_NOT_FOUND),
     *                           Enqueue 불가 상태(QUEUE_NOT_ENQUEUEABLE)이거나,
     *                           대기열이 가득 찬(QUEUE_FULL) 경우
     */
    public EnqueueResult enqueue(long tenantId, String queueId, String identifier) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        if (!queue.isEnqueueable()) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ACTIVE);
        }

        EnqueueResult result = queueEngine.enqueue(queueId, identifier);

        if (result.isFull()) {
            throw new BusinessException(ErrorCode.QUEUE_FULL);
        }

        if (result.isOk()) {
            eventPublisher.publish(EnqueueEvent.of(tenantId, queueId, result, Instant.now()));
        }

        return result;
    }

    /**
     * queue 조회 + 소유권 검증 (관리용 QueueService와 동일 패턴).
     */
    private Queue findQueueAndVerifyOwner(Long tenantId, String queueId) {
        Queue queue = queueRepository.findByQueueId(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
        if (!queue.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_OWNED);
        }
        return queue;
    }

}
