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

    // columnDefinition으로 CHAR를 명시한다. Hibernate는 String을 VARCHAR로만 매핑하므로
    // 생략하면 ddl-auto: update가 CHAR(64)를 VARCHAR(64)로 바꿔버린다.
    // SHA-256 hex는 길이가 정확히 64로 고정이라 CHAR가 맞다.
    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "CHAR(64)")
    private String tokenHash;

    // 다른 테이블과 같은 밀리초 정밀도. 생략하면 Hibernate 기본값(6)으로 넓어진다.
    @Column(name = "issued_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", columnDefinition = "DATETIME(3)")
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
