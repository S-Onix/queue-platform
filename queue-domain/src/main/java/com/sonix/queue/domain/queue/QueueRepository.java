package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueRepository {
    Queue save(Queue queue);
    Optional<Queue> findById(Long id);
    Optional<Queue> findByQueueId(String queueId);
    List<Queue> findAllByTenantId(Long tenantId);

    /**
     * 이유: 전체 큐 목록 — <b>스케줄 잡이 큐를 순회할 때</b> 쓴다(FRS §10).
     * 문제: {@code SCAN queue:*} 은 Cluster 에서 <b>접속한 노드만</b> 훑어 다른 마스터의 큐가 조용히 누락된다.
     * 해결: DB 에서 읽는다. <b>상태로 거르지 않는다</b> — 걸면 정리 실패한 큐가 영영 안 보인다.
     * 🪤 <b>PAUSED 를 거르는 것은 특히 금지</b> — 멈춰둔 큐가 마스터를 무기한 점유해
     *    같은 마스터의 다른 테넌트가 OOM 으로 죽는다(§87 에서 재현된 경로).
     *
     * @author sonix
     */
    List<Queue> findAll();
    boolean existsByTenantIdAndName(Long tenantId, String name);

    /**
     * 이유: 테넌트가 보유한 큐 수. <b>DELETED 는 세지 않는다</b>.
     * 원인: 상한의 목적이 Redis 마스터 용량이고(§75 D27-3), 삭제된 큐 자리는 회수 배치가 비운다.
     * 해결: 세지 않는다 — 세면 큐를 지우고 <b>다른 이름으로</b> 다시 만드는 정상 운용이 막힌다.
     * ⚠️ 같은 이름으로 다시 만드는 것은 이 결정과 무관하게 막힌다 —
     *    {@code uq_queues_tenant_name (tenant_id, name)} 이 DELETED 행도 들고 있다.
     *
     * @author sonix
     */
    int countActiveByTenantId(Long tenantId);
}
