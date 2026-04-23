package com.sonix.queue.api.apikey.dto;

import lombok.Getter;

@Getter
public class ApiKeyIssueResponse {
    private String apiKeyId;
    private String rawKey;
    private String message;

    private ApiKeyIssueResponse(String apiKeyId, String rawKey) {
        this.apiKeyId = apiKeyId;
        this.rawKey = rawKey;
        this.message = "이 키는 지금만 표시됩니다. 안전한 곳에 보관하세요.";
    }

    public static ApiKeyIssueResponse of(String apiKeyId, String rawKey) {
        return new ApiKeyIssueResponse(apiKeyId, rawKey);
    }
}
