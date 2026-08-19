package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueRepository {
    Queue save(Queue queue);
    Optional<Queue> findById(Long id);
    Optional<Queue> findByQueueId(String queueId);
    List<Queue> findAllByTenantId(Long tenantId);

    /**
     * 전체 큐 목록. <b>스케줄 잡이 큐를 순회할 때 쓴다</b>(FRS §10).
     *
     * <p>Redis {@code SCAN queue:*}으로 대신하지 않는 이유는 Cluster에서 {@code SCAN}이
     * <b>접속한 노드만</b> 훑기 때문이다. 마스터마다 따로 돌리지 않으면 다른 노드에 사는 큐가
     * 조용히 누락되고, 누락된 큐의 토큰은 아무 에러도 없이 복귀되지 않는다 (§80 ⑧).
     *
     * <p>상태로 거르지 않는다 — {@code DELETED}는 소프트 삭제라 Redis 키가 남아 있고,
     * 순회 대상에서 빼면 그 큐의 {@code admitted} ZSet이 영영 비워지지 않는다.
     */
    List<Queue> findAll();
    boolean existsByTenantIdAndName(Long tenantId, String name);
}
