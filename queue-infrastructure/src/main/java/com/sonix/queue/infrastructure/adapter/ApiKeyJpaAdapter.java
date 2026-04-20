package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import com.sonix.queue.infrastructure.entity.ApiKeyEntity;
import com.sonix.queue.infrastructure.repository.ApiKeyJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ApiKeyJpaAdapter implements ApiKeyRepository {

    private final ApiKeyJpaRepository apiKeyJpaRepository;

    public ApiKeyJpaAdapter(ApiKeyJpaRepository apiKeyJpaRepository) {
        this.apiKeyJpaRepository = apiKeyJpaRepository;
    }


    @Override
    public ApiKey save(ApiKey apiKey) {
        ApiKeyEntity entity = ApiKeyEntity.fromDomain(apiKey);
        ApiKeyEntity saved = apiKeyJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<ApiKey> findById(Long id) {
        return apiKeyJpaRepository.findById(id)
                .map(ApiKeyEntity::toDomain);
    }

    @Override
    public Optional<ApiKey> findByApiKeyId(String apiKeyId) {
        return apiKeyJpaRepository.findByApiKeyId(apiKeyId)
                .map(ApiKeyEntity::toDomain);
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return apiKeyJpaRepository.findByKeyHash(keyHash)
                .map(ApiKeyEntity::toDomain);
    }

    @Override
    public List<ApiKey> findAllByTenantId(Long tenantId) {

        return apiKeyJpaRepository.findAllByTenantId(tenantId)
                .stream()
                .map(ApiKeyEntity::toDomain)
                .toList();
    }
}
