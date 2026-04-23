package com.sonix.queue.api.security;

import lombok.Getter;

@Getter
public class TenantAuth {
    private final Long id;
    private final String tenantId;

    public TenantAuth(final Long id, final String tenantId) {
        this.id = id;
        this.tenantId = tenantId;
    }
}
