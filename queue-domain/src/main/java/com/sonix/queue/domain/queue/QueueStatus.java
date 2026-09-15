package com.sonix.queue.domain.queue;

public enum QueueStatus {

    /** 정상. enqueue 가능한 <b>유일한</b> 상태다({@code isEnqueueable()}). */
    ACTIVE(0),

    /** 신규 enqueue 차단(Q004, 503). <b>기존 대기자는 그대로 유지</b>되고 admit도 계속 나간다. */
    PAUSED(1),

    // 2는 결번이다 DRAINING. 도달도 탈출도 불가능한 상태였다 —
    // drain()은 ACTIVE만 받는데 프로덕션 호출이 0건이었고, delete()는 PAUSED만 받아
    // DRAINING에서는 빠져나올 수도 없었다(DRAINING → DELETED 배치도 없다).
    // "순차 배출"이 필요해지면 PAUSED가 이미 그 일을 한다(신규만 막고 기존은 흘린다).
    // 🔴 2를 다른 의미로 재사용하지 마라 — queues.status는 TINYINT라 과거 행의 뜻이 바뀐다.
    //    schema.sql의 status 주석과 짝이다. (TokenStatus 3번 결번과 같은 처리)

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
