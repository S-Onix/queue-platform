package com.sonix.queue.common.exception;

public enum ErrorCode {

    INTERNAL_SERVER_ERROR("I004", "서버 오류가 발생했습니다.", 500),
    DUPLICATE_EMAIL("T001", "이미 존재하는 이메일입니다.", 409),
    TENANT_NOT_FOUND("T002", "Tenant를 찾을 수 없습니다.", 404),
    INVALID_PASSWORD("T003", "비밀번호가 일치하지 않습니다.", 401),
    API_KEY_NOT_FOUND("A001", "API Key를 찾을 수 없습니다.", 404),
    API_KEY_NOT_OWNED("A002", "본인의 API Key가 아닙니다.", 403),
    INVALID_TOKEN("T004", "유효하지 않은 토큰입니다.", 401),
    QUEUE_NOT_FOUND("Q001", "대기열을 찾을 수 없습니다.", 404),
    QUEUE_NOT_OWNED("Q002", "본인의 대기열이 아닙니다.", 403),
    QUEUE_NOT_ACTIVE("Q004", "현재 진입할 수 없는 대기열입니다", 503),
    QUEUE_FULL("Q005", "대기열이 가득 찼습니다", 429),
    DUPLICATE_QUEUE_NAME("Q003", "이미 존재하는 대기열 이름입니다.", 409),
    AK_001_UNAUTHORIZED("AK001", "인증이 필요합니다.", 401),
    AK_002_FORBIDDEN("AK002", "권한이 없습니다.", 403),
    RL_001_KEY_LIMIT("RL001", "요청 한도를 초과했습니다.", 429),
    QUEUE_ENGINE_UNAVAILABLE("QE001", "대기열 처리 중 일시적 오류입니다. 잠시 후 재시도하세요.", 503),
    TOKEN_NOT_FOUND("TK001", "대기 토큰을 찾을 수 없습니다.", 404);


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
