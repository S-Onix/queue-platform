package com.sonix.queue.api.com.sonix.queue.api.apikey;

import com.sonix.queue.api.com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiKeyController {
    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/api/v1/tenants/me/api-keys")
    public ApiResponse<ApiKeyIssueResponse> issue(@RequestParam Long tenantId){
        ApiKeyIssueResponse response = apiKeyService.issueApiKey(tenantId);
        return ApiResponse.ok(response);
    }

}
