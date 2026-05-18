package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
public class TenantEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Long id;
    String tenantId;
    String email;
    String passwordHash;
    String name;
    int status;
    LocalDateTime createdAt;


    protected TenantEntity() {}

    public Tenant toDomain() {
        return Tenant.reconstruct(this.id,this.tenantId, this.email
                , this.passwordHash, this.name
                , TenantStatus.fromCode(status), this.createdAt
        );
    }

    public static TenantEntity fromDomain(Tenant tenant) {
        TenantEntity entity = new TenantEntity();
        entity.id = tenant.getId();
        entity.tenantId = tenant.getTenantId();
        entity.email = tenant.getEmail();
        entity.passwordHash = tenant.getPasswordHash();
        entity.name = tenant.getName();
        entity.status = tenant.getStatus().getStatusCode();
        entity.createdAt = tenant.getCreatedAt();

        return entity;
    }

}
