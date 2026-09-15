package com.sonix.queue.domain.queue;

public enum TokenStatus {
    /** 줄 서 있음. {@code waiting} ZSet에 있고 순번(seq)을 받은 상태. */
    WAITING(0),

    /**
     * 입장권(admitToken) 발급됨. <b>입장한 것이 아니다</b> — 60초 안에 쓰지 않으면 끝난다(§36).
     * 🔴 <b>TTL이 만료돼도 DB는 이 값에 머문다.</b> 300초 뒤 {@code ReconcileJob}이 4로 확정한다.
     */
    ADMIT_ISSUED(1),

    /** 입장 완료. {@code verify} 응답 시점 또는 {@code complete} 통보로 확정된다. */
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

