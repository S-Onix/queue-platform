package com.sonix.queue.api.queue.dto;

/**
 * 이유: verify 응답(FRS §6.5). <b>응답을 주는 시점이 곧 완료</b>라 COMPLETED 를 발행한다.
 * 🔑 유효한 admitToken 이면 DB {@code status=2} 로 전이하고 200 을 준다(§92).
 * 🪤 무효하면 이 DTO 가 아니라 404({@code TK002}) 다 — 성공만 표현한다.
 *
 * @author sonix
 */
public record VerifyResponse(boolean valid, String identifier) {

    public static VerifyResponse ok(String identifier) {
        return new VerifyResponse(true, identifier);
    }
}
