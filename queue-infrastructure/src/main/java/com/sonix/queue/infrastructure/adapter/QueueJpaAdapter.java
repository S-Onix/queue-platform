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
     * <p><b>이 메서드는 replica로 간다</b>(2026-08-27 라우팅 로그 실측: 20초에 replica 9회
     * = {@code TokenReclaimJob} 2초 주기 10회와 일치). {@code SimpleJpaRepository}가 <b>직접 구현한</b>
     * CRUD 메서드라 클래스 레벨 {@code @Transactional(readOnly = true)}가 실제로 걸린다.
     *
     * <p>🪤 <b>같은 리포지토리의 파생 쿼리는 반대다.</b> {@code findByQueueId}·{@code findByKeyHash}를
     * 트랜잭션 없이 부르면 readOnly 트랜잭션이 <b>열리지 않아 master</b>로 간다(같은 날 실측).
     * "Spring Data니까 replica"로 뭉뚱그리지 마라 — <b>CRUD냐 파생 쿼리냐가 갈린다</b>. CLAUDE.md §4-3.
     *
     * <p>🔴 <b>그래서 여기에 복제 지연이 붙는다.</b> {@code ReconcileJob}·{@code TokenReclaimJob}이
     * 이걸로 큐 목록을 얻으므로, <b>새로 만든 큐가 복제 도착 전이면 그 주기의 회수·대사에서 빠진다.</b>
     * 다음 주기(2초)에 잡히므로 자기 치유되지만, 복제가 <b>중단</b>되면 무음으로 낡은 목록을 돈다.
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
