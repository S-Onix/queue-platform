package com.sonix.queue.api.tenant.dto;

import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.domain.tenant.Tenant;
import lombok.Getter;

@Getter
public class SignupResponse {
    private String tenantId;
    private String email;
    private String name;
    private ApiKeyIssueResponse apiKey;

    private SignupResponse(String tenantId, String email, String name, ApiKeyIssueResponse apiKey){
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.apiKey = apiKey;
    }

    public static SignupResponse of(Tenant tenant, ApiKeyIssueResponse apiKey) {
        return new SignupResponse(
                tenant.getTenantId(),
                tenant.getEmail(),
                tenant.getName(),
                apiKey
        );
    }
}
