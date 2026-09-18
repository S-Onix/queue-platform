package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "queues")
public class QueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    // schema.sql이 정의한 폭을 명시한다. 생략하면 Hibernate 기본값(255)이 적용되어
    // ddl-auto: update가 컬럼을 넓혀버리고, validate 환경에서는 기동이 막힌다.
    @Column(length = 50)
    String queueId;
    Long tenantId;
    @Column(length = 100)
    String name;
    int maxCapacity;
    int waitingTtl;
    int inactiveTtl;
    /**
     * TINYINT 매핑.
     *
     * <p>schema.sql이 TINYINT로 정의한 컬럼이다(값 범위가 좁아 저장공간·비교 성능을 아끼려는
     * 의도적 선택). 자바 int/Integer는 기본적으로 INTEGER로 매핑되므로 명시하지 않으면
     * {@code ddl-auto: validate}가 타입 불일치로 기동을 거부하고, {@code update}는 반대로
     * 컬럼을 INT로 바꿔버려 그 의도를 조용히 되돌린다.
     */
    @JdbcTypeCode(SqlTypes.TINYINT)
    int status;
    LocalDateTime createdAt;
    LocalDateTime deletedAt;

    /**
     * 이유: 이 큐의 Redis 상태가 사는 클러스터 번호(§75).
     * 🔑 <b>도메인 {@code Queue} 엔 이 필드가 없다</b> — 배정·기록은 어댑터 안에 갇힌다(헥사고날).
     * 🔴 <b>{@code updatable = false} 다</b> — {@code save()} 가 새 detached 엔티티를 만들어 merge 하므로
     *    수정 대상이면 <b>큐 이름만 바꿔도 배정 기록이 기본값으로 덮인다</b>.
     * 해결: 값은 INSERT 시점에 한 번만 정해진다(§75 D27-2 — 큐는 클러스터를 옮기지 않는다).
     */
    @Column(name = "redis_cluster_no", updatable = false)
    @JdbcTypeCode(SqlTypes.TINYINT)
    int redisClusterNo = 1;

    protected QueueEntity() {}

    /**
     * INSERT 직전 클러스터 배정을 기록한다. {@code updatable = false}라 이후 호출은 DB에
     * 반영되지 않는다 — 신규 저장 경로에서만 호출할 것.
     */
    public void assignRedisCluster(int clusterNo) {
        this.redisClusterNo = clusterNo;
    }

    public Queue toDomain() {
        return Queue.reconstruct(this.id, this.queueId, this.tenantId
                , this.name, this.maxCapacity
                , this.waitingTtl, this.inactiveTtl
                , QueueStatus.fromCode(this.status)
                , this.createdAt, this.deletedAt);
    }

    public static QueueEntity fromDomain(Queue queue) {
        QueueEntity entity = new QueueEntity();
        entity.id = queue.getId();
        entity.queueId = queue.getQueueId();
        entity.tenantId = queue.getTenantId();
        entity.name = queue.getName();
        entity.maxCapacity = queue.getMaxCapacity();
        entity.waitingTtl = queue.getWaitingTtl();
        entity.inactiveTtl = queue.getInactiveTtl();
        entity.status = queue.getStatus().getStatusCode();
        entity.createdAt = queue.getCreatedAt();
        entity.deletedAt = queue.getDeletedAt();

        return entity;
    }
}
