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
     * 이유: 전체 큐 목록. 🔴 <b>상속받은 {@code findAll} 을 쓰지 마라 — 그건 replica 로 간다.</b>
     * 원인: 상속 CRUD 에는 클래스 레벨 {@code readOnly} 가 걸려 있고, 선언한 파생 쿼리는 트랜잭션이
     *       안 열려 master 다 — 기준은 <b>"readOnly 트랜잭션이 열렸는가" 하나</b>다(§4-3).
     * 🔴 <b>왜 master 여야 하나</b>: 배치 <b>루프 최상단이라 감싸는 try 가 없다</b> — replica 가 죽으면
     *    회수 3경로가 멈추고 종착점은 <b>enqueue 503</b> 인데 그때까지 아무 신호도 없다.
     * ⚠️ 반대로 {@code readOnly} 로 붙이지도 마라 — 순회 내내 DB 커넥션을 잡는다.
     */
   List<QueueEntity> findAllBy();

    Optional<QueueEntity> findByQueueId(String queueId);
    List<QueueEntity> findAllByTenantId(Long tenantId);
    boolean existsByTenantIdAndName(Long tenantId, String name);

    /**
     * 이유: 테넌트가 보유한 큐 수(DELETED 제외). 큐 생성 상한 판정용.
     * 🔑 <b>master 로 가는 것이 여기서는 필수다</b> — 방금 만든 큐가 안 세어지면 상한을 넘겨 통과한다.
     * 🪤 <b>근거를 "파생 쿼리라서"로 적지 마라</b> — 안 열리는 실제 이유는 호출자가 package-private 이라
     *    {@code @Transactional} 이 <b>아예 안 걸리는 것</b>이다({@code publicMethodsOnly=true}).
     * 🔴 그래서 이 COUNT 를 {@code readOnly=true} 메서드에서 재사용하는 순간 replica 로 가고
     *    <b>복제 지연 안에 만든 큐가 안 세어져 상한이 조용히 뚫린다</b>. 아무 테스트도 안 빨개진다.
     */
    int countByTenantIdAndStatusNot(Long tenantId, int status);

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
