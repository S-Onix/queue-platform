package com.sonix.queue.api.com.sonix.queue.api.tenant.dto;

import com.sonix.queue.domain.tenant.Tenant;
import lombok.Getter;

@Getter
public class LoginResponse {
    private String tenantId;

    public static LoginResponse from(Tenant tenant){
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.tenantId = tenant.getTenantId();
        return loginResponse;
    }

}
