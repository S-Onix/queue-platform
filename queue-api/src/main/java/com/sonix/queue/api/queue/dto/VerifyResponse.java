package com.sonix.queue.api.queue.dto;

/**
 * Verify 응답 (FRS §6.5). <b>이 응답을 주는 시점이 곧 완료다</b> — {@code COMPLETED}를 발행한다(PR #48).
 *
 * <p>⚠️ 구 서술 "상태 변경 없음"·"Redis 쓰기 0회"는 둘 다 거짓이다. DB 직접 쓰기는 0회(이벤트만
 * 내고 컨슈머가 {@code status=2}로 올린다)지만, Redis는 회차 키 넷을 정리한다 — {@code admit-by-admit}만
 * 남겨 같은 admitToken의 재시도가 60초 안에 통과한다(§92).
 *
 * <p>무효한 admitToken은 이 DTO가 아니라 404({@code TK002})로 나간다. 즉 {@code valid}는
 * 항상 true다 — false를 실어 200으로 내보내면 Tenant가 성공/실패를 본문까지 열어봐야 한다.
 */
public record VerifyResponse(boolean valid, String identifier) {

    public static VerifyResponse ok(String identifier) {
        return new VerifyResponse(true, identifier);
    }
}
