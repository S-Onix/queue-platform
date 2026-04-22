package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.infrastructure.entity.QueueEntity;
import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class QueueJpaAdapter implements QueueRepository {

    private final QueueJpaRepository queueJpaRepository;

    public QueueJpaAdapter(QueueJpaRepository queueJpaRepository) {
        this.queueJpaRepository = queueJpaRepository;
    }

    @Override
    public Queue save(Queue queue) {
        QueueEntity entity = QueueEntity.fromDomain(queue);
        QueueEntity saved = queueJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Queue> findById(Long id) {
        return queueJpaRepository.findById(id)
                .map(QueueEntity::toDomain);
    }

    @Override
    public Optional<Queue> findByQueueId(String queueId) {
        return queueJpaRepository.findByQueueId(queueId)
                .map(QueueEntity::toDomain);
    }

    @Override
    public List<Queue> findAllByTenantId(Long tenantId) {
        return queueJpaRepository.findAllByTenantId(tenantId)
                .stream()
                .map(QueueEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByTenantIdAndName(Long tenantId, String name) {
        return queueJpaRepository.existsByTenantIdAndName(tenantId, name);
    }
}
