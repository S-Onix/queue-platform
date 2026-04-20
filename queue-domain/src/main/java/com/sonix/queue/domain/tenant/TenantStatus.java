package com.sonix.queue.domain.tenant;

public enum TenantStatus {
      ACTIVE(0)
    , DEACTIVATED(1);


    private final int statusCode;

    TenantStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    public static TenantStatus fromCode(int code) {
        for(TenantStatus status : values()) {
            if(status.getStatusCode() == code) return status;
        }
        throw new IllegalArgumentException("해당 코드에 맞는 상태가 존재하지 않습니다.");
    }

    public int getStatusCode(){
        return this.statusCode;
    }

}
