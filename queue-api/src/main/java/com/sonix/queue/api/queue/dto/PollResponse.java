package com.sonix.queue.api.queue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sonix.queue.api.queue.PollResult;

/**
 * 개인 상태 응답 (FRS §6.3 ②). 차례 근처 + keepalive(30~60초 1회)에만 호출된다.
 *
 * <p>대기 중이면 {@code {"ready": false}}, admit됐으면 {@code {"ready": true, "admitToken": "..."}}.
 * 순번·대기 인원·다음 폴링 간격은 여기 없다 — 전원 동일값이라 {@code /status}로 갔다 (§79).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PollResponse {
    private final boolean ready;          // 입장 준비(admitToken 발급)
    private final String  admitToken;     // ready=true일 때만, else null

    private PollResponse(boolean ready, String admitToken) {
        this.ready = ready;
        this.admitToken = admitToken;
    }

    public static PollResponse from(PollResult result) {
        return new PollResponse(result.ready(), result.admitToken());
    }

    public boolean isReady()          { return ready; }
    public String  getAdmitToken()    { return admitToken; }
}
