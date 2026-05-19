package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.auth.RefreshToken;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshTokenEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected RefreshTokenEntity() {

    }

    public RefreshToken toDomain() {
        return RefreshToken.reconstruct(
                id, tenantId, tokenHash, issuedAt, expiresAt, revokedAt
        );
    }

    public static RefreshTokenEntity fromDomain(RefreshToken domain) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = domain.getId();
        entity.tenantId = domain.getTenantId();
        entity.tokenHash = domain.getTokenHash();
        entity.issuedAt = domain.getIssuedAt();
        entity.expiresAt = domain.getExpiresAt();
        entity.revokedAt = domain.getRevokedAt();

        return entity;
    }


}
