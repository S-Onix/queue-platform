package com.sonix.queue.domain.apikey;

public enum ApiKeyStatus {
    /** 사용 가능. {@code X-API-Key} 인증이 통과하는 유일한 상태다. */
    ACTIVE(0),

    /** 폐기됨. 되돌리는 전이는 없다(단방향) — 다시 쓰려면 새 키를 발급한다. */
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
