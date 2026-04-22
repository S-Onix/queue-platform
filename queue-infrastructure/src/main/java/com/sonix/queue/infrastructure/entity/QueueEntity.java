package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "queues")
public class QueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String queueId;
    Long tenantId;
    String name;
    int maxCapacity;
    int sliceCount;
    int waitingTtl;
    int inactiveTtl;
    int status;
    LocalDateTime createdAt;
    LocalDateTime deletedAt;

    protected QueueEntity() {}

    public Queue toDomain() {
        return Queue.reconstruct(this.id, this.queueId, this.tenantId
                , this.name, this.maxCapacity, this.sliceCount
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
        entity.sliceCount = queue.getSliceCount();
        entity.waitingTtl = queue.getWaitingTtl();
        entity.inactiveTtl = queue.getInactiveTtl();
        entity.status = queue.getStatus().getStatusCode();
        entity.createdAt = queue.getCreatedAt();
        entity.deletedAt = queue.getDeletedAt();

        return entity;
    }
}
