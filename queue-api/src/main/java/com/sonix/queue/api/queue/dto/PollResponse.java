package com.sonix.queue.api.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sonix.queue.api.queue.PollResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PollResponse {
    private final boolean ready;          // 입장 준비(admitToken 발급). Admit 전엔 항상 false
    private final String  admitToken;     // ready=true일 때만, else null
    private final long    frontSeq;       // 큐 맨앞 seq (스냅샷) → SDK가 rank=mySeq-frontSeq
    private final long    total;          // 대기 인원 (스냅샷)
    // 서버가 등급(내부 rank)에 지터까지 얹어 확정한 값이다. SDK는 그대로 지킨다.
    // SDK가 여기에 다시 ±지터를 걸면 안 된다 — 서버는 등급 하한 위로만 흩는데(Rate Limit
    // refill과 맞물려 있어서), 클라이언트가 아래로 흩으면 그 하한이 깨진다.
    private final int     nextPollAfterSec;


    private PollResponse(boolean ready, String admitToken, long frontSeq,
                         long total, int nextPollAfterSec) {
        this.ready = ready;
        this.admitToken = admitToken;
        this.frontSeq = frontSeq;
        this.total = total;
        this.nextPollAfterSec = nextPollAfterSec;
    }

    public static PollResponse from(PollResult result) {
        return new PollResponse(
                result.ready(), result.admitToken(), result.frontSeq(), result.total(), result.nextPollAfterSec());
    }

    public boolean isReady()          { return ready; }
    public String  getAdmitToken()    { return admitToken; }
    public long    getFrontSeq()      { return frontSeq; }
    public long    getTotal()         { return total; }
    public int     getNextPollAfterSec() { return nextPollAfterSec; }
}
