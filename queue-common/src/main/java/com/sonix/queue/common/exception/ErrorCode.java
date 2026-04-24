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
    DUPLICATE_QUEUE_NAME("Q003", "이미 존재하는 대기열 이름입니다.", 409);

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
