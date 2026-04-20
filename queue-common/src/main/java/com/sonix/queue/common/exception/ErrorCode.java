package com.sonix.queue.common.exception;

public enum ErrorCode {

    INTERNAL_SERVER_ERROR("I004", "서버 오류가 발생했습니다.", 500);

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
