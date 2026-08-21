package com.sonix.queue.domain.queue;

public enum TokenStatus {
    WAITING(0),
    ADMIT_ISSUED(1),
    COMPLETED(2),
    // 3은 결번이다 — CANCELLED. Cancel API를 만들지 않기로 확정해(§82) 도달 경로가 없다.
    //   한 행도 존재한 적이 없으므로 상수를 지웠다. **3을 다른 의미로 재사용하지 마라** —
    //   schema.sql의 status 주석과 짝이다.
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

