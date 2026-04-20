package com.sonix.queue.domain.tenant;

import java.util.Optional;

public interface TenantRepository {
    Tenant save(Tenant tenant);
    Optional<Tenant> findById(Long id);
    Optional<Tenant> findByTenantId(String tenantId);
    Optional<Tenant> findByEmail(String email);
    boolean existsByEmail(String email);

}
