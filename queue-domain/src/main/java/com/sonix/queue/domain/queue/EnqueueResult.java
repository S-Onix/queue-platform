package com.sonix.queue.domain.queue;

import lombok.Getter;

@Getter
public class EnqueueResult {
    public enum Status {
        OK,
        EXISTS,
        FULL
    }

    private final Status status;
    private final long rank;      // 0-based (없으면 -1)
    private final long total;     // 전체 대기열 크기
    private final String identifier;

    private EnqueueResult(Status status, long rank, long total, String identifier) {
        this.status = status;
        this.rank = rank;
        this.total = total;
        this.identifier = identifier;
    }

    /**
     * 신규 진입 성공.
     */
    public static EnqueueResult ok(String identifier, long rank, long total) {
        return new EnqueueResult(Status.OK, rank, total, identifier);
    }

    /**
     * 이미 대기 중 (중복 방지).
     */
    public static EnqueueResult exists(String identifier, long rank, long total) {
        return new EnqueueResult(Status.EXISTS, rank, total, identifier);
    }



    /**
     * 대기열 초과 (거부).
     */
    public static EnqueueResult full(String identifier, long total) {
        return new EnqueueResult(Status.FULL, -1, total, identifier);
    }

    public boolean isSuccess() {
        return status == Status.OK || status == Status.EXISTS;
    }

    public boolean isDuplicate() {
        return status == Status.EXISTS;
    }

    public boolean isFull() {
        return status == Status.FULL;
    }

    public boolean isExists() {
        return status == Status.EXISTS;
    }

    /**
     * 1-based 순번 (UX용).
     * rank가 -1이면 -1 반환.
     */
    public long getDisplayRank() {
        return rank < 0 ? -1 : rank + 1;
    }
}
