package com.sonix.queue.api.queue.dto;

/**
 * Verify 응답 (FRS §6.5). <b>상태 변경 없음</b> — Redis 쓰기 0회, DB 쓰기 0회.
 *
 * <p>무효한 admitToken은 이 DTO가 아니라 404({@code TK002})로 나간다. 즉 {@code valid}는
 * 항상 true다 — false를 실어 200으로 내보내면 Tenant가 성공/실패를 본문까지 열어봐야 한다.
 */
public record VerifyResponse(boolean valid, String identifier) {

    public static VerifyResponse ok(String identifier) {
        return new VerifyResponse(true, identifier);
    }
}
