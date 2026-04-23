package com.sonix.queue.api.tenant.dto;

import com.sonix.queue.domain.tenant.Tenant;
import lombok.Getter;

@Getter
public class TenantResponse {

    private String tenantId;
    private String email;
    private String name;


    public static TenantResponse from(Tenant tenant) {
        TenantResponse response = new TenantResponse();
        response.tenantId = tenant.getTenantId();
        response.email = tenant.getEmail();
        response.name = tenant.getName();

        return response;
    }
}
