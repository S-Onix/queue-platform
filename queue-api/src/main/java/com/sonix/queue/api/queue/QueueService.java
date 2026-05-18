package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueService {
    private final QueueRepository queueRepository;

    public QueueService(QueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Transactional
    QueueResponse createQueue(Long tenantId, QueueCreateRequest request) {
        boolean isExist = queueRepository.existsByTenantIdAndName(tenantId, request.getName());
        if(isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME);
        }

        Queue queue = Queue.create(tenantId, request.getName(), request.getMaxCapacity(), request.getWaitingTtl(), request.getInactiveTtl());
        queueRepository.save(queue);
        return QueueResponse.from(queue);
    }

    @Transactional(readOnly = true)
    public QueueResponse getQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse updateQueue(Long tenantId, String queueId, QueueUpdateRequest request) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        queue.update(request.getName());
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse pauseQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        queue.pause();
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse resumeQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        queue.resume();
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse deleteQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        queue.delete();
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }


    private Queue findQueueAndVerifyOwner(Long tenantId, String queueId) {
        Queue queue = queueRepository.findByQueueId(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
        if (!queue.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_OWNED);
        }
        return queue;
    }
}
