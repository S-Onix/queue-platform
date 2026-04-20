package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.entity.TenantEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantJpaRepository tenantJpaRepository;

    public TenantRepositoryImpl(TenantJpaRepository tenantJpaRepository) {
        this.tenantJpaRepository = tenantJpaRepository;
    }


    @Override
    public Tenant save(Tenant tenant) {
        TenantEntity entity = TenantEntity.fromDomain(tenant);
        TenantEntity saved = tenantJpaRepository.save(entity);

        return saved.toDomain();
    }

    @Override
    public Optional<Tenant> findById(Long id) {
        return tenantJpaRepository.findById(id)
                .map(TenantEntity::toDomain);
    }

    @Override
    public Optional<Tenant> findByTenantId(String tenantId) {
        return tenantJpaRepository.findByTenantId(tenantId)
                .map(TenantEntity::toDomain);
    }

    @Override
    public Optional<Tenant> findByEmail(String email) {
        return tenantJpaRepository.findByEmail(email)
                .map(TenantEntity::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return tenantJpaRepository.existsByEmail(email);
    }
}
