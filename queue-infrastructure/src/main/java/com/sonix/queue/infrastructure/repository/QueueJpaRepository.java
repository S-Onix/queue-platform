package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.infrastructure.entity.QueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QueueJpaRepository extends JpaRepository<QueueEntity, Long> {
    Optional<QueueEntity> findByQueueId(String queueId);
    List<QueueEntity> findAllByTenantId(Long tenantId);
    boolean existsByTenantIdAndName(Long tenantId, String name);

}
