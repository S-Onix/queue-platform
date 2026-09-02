package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.infrastructure.entity.QueueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QueueJpaRepository extends JpaRepository<QueueEntity, Long> {

    /**
     * 전체 큐 목록. 🔴 <b>상속받은 {@code findAll}을 쓰지 마라 — 그건 replica로 간다.</b>
     *
     * <p>{@code SimpleJpaRepository}가 직접 구현한 CRUD 메서드에는 그 클래스의
     * {@code @Transactional(readOnly = true)}가 걸려 있어 {@code ReplicationRoutingDataSource}가
     * <b>replica로 보낸다</b>(실측). 인터페이스에 선언한 파생 쿼리는 트랜잭션이 열리지 않아
     * <b>master</b>로 간다 — 판정 기준은 "readOnly 트랜잭션이 열렸는가" 하나다(§4-3).
     *
     * <p><b>왜 master여야 하나:</b> {@code TokenReclaimJob}(10초)과 {@code ReconcileJob}(5분)이
     * 이걸로 큐 목록을 얻는데, <b>루프 최상단이라 감싸는 try가 없다</b>. replica가 죽으면 회수
     * 3경로(admitToken TTL·inactiveTtl·waitingTtl)가 <b>통째로 멈추고</b>, §82로 Cancel을 폐기해
     * 그게 유일한 정리 경로다. 종착점은 {@code waiting} 유령 누적 → {@code maxCapacity} 도달 →
     * <b>enqueue 503</b>이며, 그때까지 아무 신호도 없다.
     *
     * <p>부수 효과로 복제 지연도 사라진다 — 새로 만든 큐가 복제 도착 전이라 <b>그 주기의 회수·대사에서
     * 빠지던</b> 창이 없어진다({@code doc/reviews/2026-09-01-replica-assumption-audit.md} D-3).
     *
     * <p>⚠️ 대신 {@code @Transactional(readOnly = true)}로 replica에 붙이는 <b>반대 방향은 안 된다</b>.
     * 잡의 루프 안에서 Redis EVAL을 치므로 순회 내내 DB 커넥션을 잡는다
     * ({@code QueueEngineService.admit}에 트랜잭션을 안 붙인 것과 같은 이유).
     *
     * <p>상태로 거르지 않는 이유는 포트 {@code QueueRepository.findAll} 주석에 있다.
     */
    List<QueueEntity> findAllBy();

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
