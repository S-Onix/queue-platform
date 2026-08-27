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
     * <p>🪤 <b>구 주석이 거짓이었다.</b> "{@code SimpleJpaRepository}가 클래스 레벨
     * {@code @Transactional(readOnly = true)}라 호출자가 트랜잭션 없이 불러도 replica로 라우팅된다"고
     * 적혀 있었으나, 2026-08-27 라우팅 로그 실측 결과 <b>트랜잭션 없이 부르면 master로 간다</b>.
     * 파생 쿼리는 readOnly 트랜잭션을 열지 않아 {@code isCurrentTransactionReadOnly()}가 false다.
     *
     * <p><b>replica로 보내려면 호출자가 {@code @Transactional(readOnly = true)}를 걸어야 한다.</b>
     * 여기에 {@code @Transactional}을 붙이지 않는 것은 트랜잭션 경계를 Service 계층에만 두는
     * 규칙 때문이며, 그 대가가 <b>이 조회가 master로 간다</b>는 것이다.
     */
    @Override
    public List<Queue> findAll() {
        return queueJpaRepository.findAll()
                .stream()
                .map(QueueEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByTenantIdAndName(Long tenantId, String name) {
        return queueJpaRepository.existsByTenantIdAndName(tenantId, name);
    }
}
