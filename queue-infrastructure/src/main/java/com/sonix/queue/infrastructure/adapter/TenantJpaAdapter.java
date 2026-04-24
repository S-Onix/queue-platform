package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.entity.TenantEntity;
import com.sonix.queue.infrastructure.repository.TenantJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TenantJpaAdapter implements TenantRepository {

    private final TenantJpaRepository tenantJpaRepository;

    public TenantJpaAdapter(TenantJpaRepository tenantJpaRepository) {
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
