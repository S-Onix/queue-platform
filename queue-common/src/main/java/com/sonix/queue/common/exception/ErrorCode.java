package com.sonix.queue.common.exception;

public enum ErrorCode {

    INTERNAL_SERVER_ERROR("I004", "서버 오류가 발생했습니다.", 500),
    DUPLICATE_EMAIL("T001", "이미 존재하는 이메일입니다.", 409),
    TENANT_NOT_FOUND("T002", "Tenant를 찾을 수 없습니다.", 404),
    INVALID_PASSWORD("T003", "비밀번호가 일치하지 않습니다.", 401);

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
