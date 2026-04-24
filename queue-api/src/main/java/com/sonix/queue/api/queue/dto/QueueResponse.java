package com.sonix.queue.api.queue.dto;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class QueueResponse {
    private String queueId;
    private String name;
    private int maxCapacity;
    private int sliceCount;
    private Integer waitingTtl;
    private Integer inactiveTtl;
    private QueueStatus status;
    private LocalDateTime createdAt;

    private QueueResponse() {}

    public static QueueResponse from(Queue queue) {
        QueueResponse response = new QueueResponse();
        response.queueId = queue.getQueueId();
        response.name = queue.getName();
        response.maxCapacity = queue.getMaxCapacity();
        response.sliceCount = queue.getSliceCount();
        response.waitingTtl = queue.getWaitingTtl();
        response.inactiveTtl = queue.getInactiveTtl();
        response.status = queue.getStatus();
        response.createdAt = queue.getCreatedAt();

        return response;
    }
}
