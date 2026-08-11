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

    protected QueueEntity() {}

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
