package com.sonix.queue.infrastructure.cache.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyStatus;

import java.time.LocalDateTime;

public abstract class ApiKeyMixin {
    @JsonCreator
    public static ApiKey reconstruct(
            @JsonProperty("id") Long id,
            @JsonProperty("apiKeyId") String apiKeyId,
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("keyHash") String keyHash,
            @JsonProperty("status") ApiKeyStatus status,
            @JsonProperty("createdAt") LocalDateTime createdAt,
            @JsonProperty("revokedAt") LocalDateTime revokedAt
    ) {
        return null;  // 사용되지 않음, 시그니처만 중요
    }
}
