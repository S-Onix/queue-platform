package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.infrastructure.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, Long> {
    Optional<ApiKeyEntity> findByApiKeyId(String apiKeyId);
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);
    List<ApiKeyEntity> findAllByTenantId(Long tenantId);

}
