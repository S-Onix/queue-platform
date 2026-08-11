package com.sonix.queue.domain.queue;

public enum TokenStatus {
    WAITING(0),
    ADMIT_ISSUED(1),
    COMPLETED(2),
    CANCELED(3),
    EXPIRED(4);

    private final int statusCode;

    TokenStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    public static TokenStatus fromCode(int code) {
        for (TokenStatus status : values()) {
            if (status.getStatusCode() == code) return status;
        }
        throw new IllegalArgumentException("해당 코드에 맞는 상태가 존재하지 않습니다.");
    }

    public int getStatusCode() {
        return this.statusCode;
    }
}

