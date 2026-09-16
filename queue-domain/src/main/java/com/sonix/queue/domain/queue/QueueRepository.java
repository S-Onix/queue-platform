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
     * <p>상태로 거르지 않는다. 다만 <b>이유가 2026-09-16에 바뀌었다.</b>
     *
     * <p>원래 근거는 "{@code DELETED}는 소프트 삭제라 Redis 키가 남아 있고, 순회 대상에서 빼면
     * 그 큐의 {@code admitted} ZSet이 영영 비워지지 않는다"였다. <b>그 전제가 사라졌다</b> —
     * 이제 삭제 시점에 지운다({@code QueueEngine.purgeDeleted}). 남기는 {@code tokens}·
     * {@code admitted}도 완료 창(300초) 뒤 만료되므로 영구 점유가 아니다.
     *
     * <p>그래도 거르지 않는 이유는 <b>거를 실익이 없어서다</b> — 지워진 큐의 순회는 빈 키 조회라
     * 무동작이고, 조건을 넣으면 "정리가 실패해 상태만 DELETED인 큐"가 영영 방치된다.
     *
     * <p>🪤 <b>PAUSED를 거르는 것은 특히 금지다.</b> PAUSED는 입구만 잠글 뿐 시간은 흐른다.
     * 회수가 멈추면 멈춰둔 큐가 Redis 마스터를 무기한 점유하고, 같은 마스터의 <b>다른 테넌트</b>가
     * OOM으로 죽는다(§87에서 재현된 그 경로다).
     */
    List<Queue> findAll();
    boolean existsByTenantIdAndName(Long tenantId, String name);

    /**
     * 테넌트가 보유한 큐 수. <b>DELETED는 세지 않는다.</b>
     *
     * <p>상한의 목적이 Redis 마스터 용량이고(§75 D27-3의 50% 임계), 삭제된 큐의 키는
     * {@code waitingTtl} 뒤 회수 배치가 비운다 — 자리를 영구히 잡고 있지 않다.
     * 세면 큐를 지우고 <b>다른 이름으로</b> 다시 만드는 정상 운용이 영구히 막힌다.
     *
     * <p>⚠️ <b>같은 이름으로 다시 만드는 것은 이 결정과 무관하게 이미 막혀 있다</b> —
     * {@code uq_queues_tenant_name (tenant_id, name)}에 status 조건이 없고
     * {@code existsByTenantIdAndName}도 status를 안 본다. 여기서 살리는 것은 이름을 바꿔
     * 다시 만드는 경우뿐이다.
     *
     * <p>⚠️ {@code findAll()}이 DELETED를 <b>거르지 않는</b> 것과 반대 방향인데, 모순이 아니다 —
     * 저쪽은 "Redis 키가 남아 있으니 순회해야 한다"이고 이쪽은 "곧 비니까 정원에 안 센다"다.
     * 배치 순회 비용은 이 상한이 막는 대상이 아니다(순회에 종료 조건이 없는 것은 별건).
     */
    int countActiveByTenantId(Long tenantId);
}
