package com.sonix.queue.domain.queue;

public enum QueueStatus {

    /** 정상. enqueue 가능한 <b>유일한</b> 상태다({@code isEnqueueable()}). */
    ACTIVE(0),

    /** 신규 enqueue 차단(Q004, 503). <b>기존 대기자는 그대로 유지</b>되고 admit도 계속 나간다. */
    PAUSED(1),

    // 이유: 2는 결번이다(옛 DRAINING). 도달도 탈출도 불가능한 상태였다.
    // 원인: drain()은 ACTIVE만 받는데 호출 0건, delete()는 PAUSED만 받아 빠져나올 수도 없었다.
    // 해결: "순차 배출"은 PAUSED가 이미 한다(신규만 막고 기존은 흘린다).
    // 🔴 2를 재사용하지 마라 — TINYINT라 과거 행의 뜻이 바뀐다(TokenStatus 3번 결번과 같다).

    /**
     * 삭제됨. <b>조회는 된다</b> — {@code findByQueueId}가 삭제를 거르지 않으므로
     * enqueue는 404가 아니라 <b>503 Q004</b>다.
     */
    DELETED(3);

    private int statusCode;

    QueueStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    public static QueueStatus fromCode(int code) {
        for(QueueStatus status : values()) {
            if(status.getStatusCode() == code) return status;
        }
        throw new IllegalArgumentException("해당 코드에 맞는 상태가 존재하지 않습니다.");
    }

    public int getStatusCode(){
        return this.statusCode;
    }
}
