package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name= "api_keys")
public class ApiKeyEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Long id;
    String apiKeyId;
    Long tenantId;
    String keyHash;
    int status;
    LocalDateTime createdAt;
    LocalDateTime revokedAt;

    protected ApiKeyEntity(){}

    public ApiKey toDomain(){
        return ApiKey.reconstruct(this.id, this.apiKeyId, this.tenantId
                , this.keyHash, ApiKeyStatus.fromCode(this.status)
                , this.createdAt, this.revokedAt);
    }

    public static ApiKeyEntity fromDomain(ApiKey apiKey) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.id = apiKey.getId();
        entity.apiKeyId = apiKey.getApiKeyId();
        entity.tenantId = apiKey.getTenantId();
        entity.keyHash = apiKey.getKeyHash();
        entity.status = apiKey.getStatus().getStatusCode();
        entity.createdAt = apiKey.getCreatedAt();
        entity.revokedAt = apiKey.getRevokedAt();

        return entity;
    }
}
