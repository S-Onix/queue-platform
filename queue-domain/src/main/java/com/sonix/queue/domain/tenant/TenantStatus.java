package com.sonix.queue.domain.tenant;

public enum TenantStatus {
    /** 정상. 로그인·API Key 인증이 통과하는 유일한 상태다. */
      ACTIVE(0)

    /** 비활성. {@code Tenant.deactivate()}로만 진입하고 <b>되돌리는 전이는 없다</b>(단방향). */
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
