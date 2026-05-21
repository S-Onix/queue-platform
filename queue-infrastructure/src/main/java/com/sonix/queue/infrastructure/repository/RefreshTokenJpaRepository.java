package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.infrastructure.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r " +
            "SET r.revokedAt = :revokedAt " +
            "WHERE r.tenantId = :tenantId " +
            "  AND r.revokedAt IS NULL")
    int revokeAllByTenantId(@Param("tenantId") Long tenantId,
                            @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.expiresAt < :before")
    int deleteByExpiresAtBefore(@Param("before") LocalDateTime before);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r " +
            "SET r.revokedAt = :revokedAt " +
            "WHERE r.tokenHash = :tokenHash AND r.revokedAt IS NULL")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash,
                          @Param("revokedAt") LocalDateTime revokedAt);
}
