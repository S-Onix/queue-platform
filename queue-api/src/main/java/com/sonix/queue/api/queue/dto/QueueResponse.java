package com.sonix.queue.api.queue.dto;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueStatus;
import lombok.Getter;

import java.time.Instant;
import java.time.ZoneOffset;

@Getter
public class QueueResponse {
    private String queueId;
    private String name;
    private int maxCapacity;
    private Integer waitingTtl;
    private Integer inactiveTtl;
    private QueueStatus status;
    /**
     * 큐 생성 시각.
     *
     * <p>{@code Instant}인 이유는 {@code ApiResponse.timestamp}와 같다 — 저장은 UTC인데
     * {@code LocalDateTime}으로 내보내면 존 표기 없이 직렬화돼 클라이언트가 자기 로컬로 읽는다.
     * 한국 클라이언트면 9시간 어긋난다. (DECISIONS §77)
     */
    private Instant createdAt;

    private QueueResponse() {}

    public static QueueResponse from(Queue queue) {
        QueueResponse response = new QueueResponse();
        response.queueId = queue.getQueueId();
        response.name = queue.getName();
        response.maxCapacity = queue.getMaxCapacity();
        response.waitingTtl = queue.getWaitingTtl();
        response.inactiveTtl = queue.getInactiveTtl();
        response.status = queue.getStatus();
        response.createdAt = queue.getCreatedAt().toInstant(ZoneOffset.UTC);

        return response;
    }
}
