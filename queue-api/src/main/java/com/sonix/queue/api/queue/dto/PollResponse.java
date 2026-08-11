package com.sonix.queue.api.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sonix.queue.api.queue.PollResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PollResponse {
    private final boolean ready;          // 입장 준비(admitToken 발급). Admit 전엔 항상 false
    private final String  admitToken;     // ready=true일 때만, else null
    private final long    frontSeq;       // 큐 맨앞 seq (스냅샷) → SDK가 rank=mySeq-frontSeq
    private final long    total;          // 대기 인원 (스냅샷)
    private final int     nextPollAfterSec; // 서버 base(내부 rank로 등급), SDK가 ±20% jitter


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
