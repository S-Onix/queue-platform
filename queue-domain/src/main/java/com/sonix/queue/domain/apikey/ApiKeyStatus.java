package com.sonix.queue.domain.apikey;

public enum ApiKeyStatus {
    ACTIVE(0),
    REVOKED(1);

    private final int statusCode;

    ApiKeyStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    public static ApiKeyStatus fromCode(int code) {
        for(ApiKeyStatus status : values()) {
            if(status.getStatusCode() == code) return status;
        }
        throw new IllegalArgumentException("해당 코드에 맞는 상태가 존재하지 않습니다.");
    }

    public int getStatusCode(){
        return this.statusCode;
    }
}
