package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueRepository {
    Queue save(Queue queue);
    Optional<Queue> findById(Long id);
    Optional<Queue> findByQueueId(String queueId);
    List<Queue> findAllByTenantId(Long tenantId);
    boolean existsByTenantIdAndName(Long tenantId, String name);
}
