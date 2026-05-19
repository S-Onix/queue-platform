package com.sonix.queue.domain.auth;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken refreshToken);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void update(RefreshToken token);
    int revokeAllByTenantId(Long tenantId, LocalDateTime revokedAt);
    int deleteByExpiresAtBefore(LocalDateTime before);
}
