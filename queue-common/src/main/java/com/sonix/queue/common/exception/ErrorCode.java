package com.sonix.queue.common.exception;

public enum ErrorCode {

    INTERNAL_SERVER_ERROR("I004", "서버 오류가 발생했습니다.", 500),
    DUPLICATE_EMAIL("T001", "이미 존재하는 이메일입니다.", 409),
    TENANT_NOT_FOUND("T002", "Tenant를 찾을 수 없습니다.", 404),
    /**
     * 로그인 실패. <b>이메일이 없든 비밀번호가 틀리든 이 하나로 답한다</b>(계정 열거 차단).
     * 그래서 메시지도 어느 쪽인지 말하지 않는다.
     */
    INVALID_CREDENTIALS("T003", "이메일 또는 비밀번호가 올바르지 않습니다.", 401),
    API_KEY_NOT_FOUND("A001", "API Key를 찾을 수 없습니다.", 404),
    API_KEY_NOT_OWNED("A002", "본인의 API Key가 아닙니다.", 403),
    INVALID_TOKEN("T004", "유효하지 않은 토큰입니다.", 401),
    QUEUE_NOT_FOUND("Q001", "대기열을 찾을 수 없습니다.", 404),
    QUEUE_NOT_OWNED("Q002", "본인의 대기열이 아닙니다.", 403),
    QUEUE_NOT_ACTIVE("Q004", "현재 진입할 수 없는 대기열입니다", 503),
    QUEUE_FULL("Q005", "대기열이 가득 찼습니다", 429),
    QUEUE_LIMIT_EXCEEDED("Q006", "테넌트당 생성 가능한 대기열 수를 초과했습니다.", 409),
    /**
     * 지금 상태에서 갈 수 없는 전이를 요청했다 (예: ACTIVE 큐에 바로 DELETE).
     *
     * <p><b>409인 이유</b>: 재시도로 해결되지 않는다. 호출자가 순서를 틀린 것이라
     * 5xx(서버 잘못 → 재시도하라)로 답하면 Tenant가 영원히 재시도한다.
     * 명세가 곧 SDK이므로 이 구분이 계약의 일부다(§35).
     *
     * <p>⚠️ {@code complete}에는 쓰지 않는다 — 거긴 원인(상태 불가 / admitToken 불일치)을
     * 구분하지 않고 404 하나로 답하기로 했다(FRS §6.6).
     */
    QUEUE_INVALID_STATUS("QE006", "현재 상태에서 수행할 수 없는 작업입니다.", 409),
    DUPLICATE_QUEUE_NAME("Q003", "이미 존재하는 대기열 이름입니다.", 409),
    AK_001_UNAUTHORIZED("AK001", "인증이 필요합니다.", 401),
    AK_002_FORBIDDEN("AK002", "권한이 없습니다.", 403),
    RL_001_KEY_LIMIT("RL001", "요청 한도를 초과했습니다.", 429),
    QUEUE_ENGINE_UNAVAILABLE("QE001", "대기열 처리 중 일시적 오류입니다. 잠시 후 재시도하세요.", 503),
    TOKEN_NOT_FOUND("TK001", "대기 토큰을 찾을 수 없습니다.", 404),
    /**
     * verify: admitToken이 Redis에도 DB에도 없다(= 발급된 적 없거나 유효 창을 넘겼다).
     * complete: (tokenId, admitToken) 짝이 완료 가능한 상태가 아니다 — 같은 뜻이라 코드를 나누지 않는다.
     */
    INVALID_ADMIT_TOKEN("TK002", "유효하지 않은 입장 토큰입니다.", 404);


    private final String code;
    private final String message;
    private final int httpStatus;

    ErrorCode(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode(){
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus(){
        return httpStatus;
    }
}
