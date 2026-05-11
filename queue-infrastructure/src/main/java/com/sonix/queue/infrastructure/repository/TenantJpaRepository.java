package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.infrastructure.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, Long> {
    Optional<TenantEntity> findByTenantId(String tenantId);
    Optional<TenantEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
