package com.sonix.queue.domain.apikey;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {
    ApiKey save(ApiKey apiKey);
    Optional<ApiKey> findById(Long id);
    Optional<ApiKey> findByApiKeyId(String apiKeyId);
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findAllByTenantId(Long tenantId);
}
