package com.sonix.queue.domain.apikey;

import com.sonix.queue.common.util.IdGenerator;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ApiKey {
    Long id;
    String apiKeyId;
    Long tenantId;
    String keyHash;
    ApiKeyStatus status;
    LocalDateTime createdAt;
    LocalDateTime revokedAt;

    private ApiKey(){}

    private ApiKey(Long tenantId, String keyHash){
        this.apiKeyId = IdGenerator.generate("ak_");
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.status = ApiKeyStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();

    }

    public static ApiKey create(Long tenantId, String keyHash){
        return new ApiKey(tenantId, keyHash);
    }

    public static ApiKey reconstruct(Long id, String apiKeyId, Long tenantId,
                                     String keyHash, ApiKeyStatus status,
                                     LocalDateTime createdAt, LocalDateTime revokedAt){
        ApiKey apiKey = new ApiKey();
        apiKey.id = id;
        apiKey.apiKeyId = apiKeyId;
        apiKey.tenantId = tenantId;
        apiKey.keyHash = keyHash;
        apiKey.status = status;
        apiKey.createdAt = createdAt;
        apiKey.revokedAt = revokedAt;

        return apiKey;
    }

    public boolean isActive() {
        return this.status != ApiKeyStatus.REVOKED;
    }

    public void revoke(){
        if(this.status != ApiKeyStatus.ACTIVE) {
            throw new IllegalStateException("활성화 상태가 아닌 API Key 입니다.");
        }
        this.status = ApiKeyStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

    public boolean matchesHash(String rawKey) {
        return ApiKeyHasher.matches(rawKey, this.keyHash);
    }

}
