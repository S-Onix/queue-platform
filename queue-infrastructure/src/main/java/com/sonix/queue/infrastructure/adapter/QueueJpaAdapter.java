package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.infrastructure.entity.QueueEntity;
import com.sonix.queue.infrastructure.queue.RedisClusterAssigner;
import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class QueueJpaAdapter implements QueueRepository {

    private final QueueJpaRepository queueJpaRepository;
    private final RedisClusterAssigner clusterAssigner;

    public QueueJpaAdapter(QueueJpaRepository queueJpaRepository, RedisClusterAssigner clusterAssigner) {
        this.queueJpaRepository = queueJpaRepository;
        this.clusterAssigner = clusterAssigner;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>신규 큐일 때만</b> Redis 클러스터를 배정해 함께 기록한다(§75). 도메인
     * {@code Queue}는 클러스터를 모르므로(순수 자바, Redis 토폴로지 비의존) 배정은 여기서 끝난다.
     * 수정 경로에서는 {@code redis_cluster_no}가 {@code updatable = false}라 손대지 않는다.
     */
    @Override
    public Queue save(Queue queue) {
        QueueEntity entity = QueueEntity.fromDomain(queue);
        if (queue.getId() == null) {
            entity.assignRedisCluster(clusterAssigner.assign());
        }
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

    /**
     * {@inheritDoc}
     *
     * <p>🔴 <b>{@code findAllBy}다. 상속받은 {@code findAll}을 쓰면 replica로 가고, replica가
     * 죽으면 회수 배치 3경로가 통째로 멈춘다</b> ({@code QueueJpaRepository} 주석 참조).
     */
    @Override
    public List<Queue> findAll() {
        return queueJpaRepository.findAllBy()
                .stream()
                .map(QueueEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByTenantIdAndName(Long tenantId, String name) {
        return queueJpaRepository.existsByTenantIdAndName(tenantId, name);
    }
}
