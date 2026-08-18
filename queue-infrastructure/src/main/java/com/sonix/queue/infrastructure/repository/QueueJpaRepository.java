package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.infrastructure.entity.QueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QueueJpaRepository extends JpaRepository<QueueEntity, Long> {
    Optional<QueueEntity> findByQueueId(String queueId);
    List<QueueEntity> findAllByTenantId(Long tenantId);
    boolean existsByTenantIdAndName(Long tenantId, String name);

    /**
     * 큐의 Redis 클러스터 배정 조회 (§75).
     *
     * <p>Redis에 아직 키가 없는 큐(생성 후 첫 enqueue)의 목적지를 정할 때만 쓴다.
     * {@code uq_queues_queue_id}를 타는 const 조회이며, (WAS, queueId)당 평생 1회다.
     */
    @Query("select q.redisClusterNo from QueueEntity q where q.queueId = :queueId")
    Optional<Integer> findRedisClusterNoByQueueId(@Param("queueId") String queueId);

    /**
     * 지금까지 배정된 가장 높은 클러스터 번호 (신규 큐 배정의 단조증가 가드, §75 D29).
     *
     * <p>한 번 cluster2로 넘어갔으면 cluster1의 사용률이 다시 내려가도 되돌아가지 않는다.
     * 히스테리시스를 위한 별도 상태·키·테이블이 필요 없다 — 이미 있는 컬럼이 그 기록이다.
     *
     * <p>큐 생성(cold path)에서만 호출한다. 큐 수는 수천 단위라 full scan이어도 무해하다.
     */
    @Query("select coalesce(max(q.redisClusterNo), 1) from QueueEntity q")
    int findMaxRedisClusterNo();
}
