package com.sonix.queue.domain.queue;

import com.sonix.queue.domain.tenant.TenantStatus;

public enum QueueStatus {
    ACTIVE(0),
    PAUSED(1),
    DRAINING(2),
    DELETED(3);

    private int statusCode;

    QueueStatus(int statusCode) {
        this.statusCode = statusCode;
    }

    public static QueueStatus fromCode(int code) {
        for(QueueStatus status : values()) {
            if(status.getStatusCode() == code) return status;
        }
        throw new IllegalArgumentException("해당 코드에 맞는 상태가 존재하지 않습니다.");
    }

    public int getStatusCode(){
        return this.statusCode;
    }
}
