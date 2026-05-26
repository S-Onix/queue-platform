package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.auth.RefreshToken;
import com.sonix.queue.domain.auth.RefreshTokenRepository;
import com.sonix.queue.infrastructure.entity.RefreshTokenEntity;
import com.sonix.queue.infrastructure.repository.RefreshTokenJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class RefreshTokenJpaAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository refreshTokenJpaRepository;

    public RefreshTokenJpaAdapter(RefreshTokenJpaRepository refreshTokenJpaRepository) {
        this.refreshTokenJpaRepository = refreshTokenJpaRepository;
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        RefreshTokenEntity entity = RefreshTokenEntity.fromDomain(refreshToken);
        RefreshTokenEntity saved = refreshTokenJpaRepository.save(entity);

        return saved.toDomain();
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {

        return refreshTokenJpaRepository.findByTokenHash(tokenHash)
                .map(RefreshTokenEntity::toDomain);

    }

    @Override
    public int revokeByTokenHash(String tokenHash, LocalDateTime revokedAt) {
        return refreshTokenJpaRepository.revokeByTokenHash(tokenHash, revokedAt);
    }

    @Override
    public int revokeAllByTenantId(Long tenantId, LocalDateTime revokedAt) {
        return refreshTokenJpaRepository.revokeAllByTenantId(tenantId, revokedAt);
    }

    @Override
    public int deleteByExpiresAtBefore(LocalDateTime before) {
        return refreshTokenJpaRepository.deleteByExpiresAtBefore(before);
    }
}
