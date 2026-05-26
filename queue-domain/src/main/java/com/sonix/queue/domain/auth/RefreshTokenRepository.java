package com.sonix.queue.domain.auth;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository {

    /**
     * 새 Refresh Token 저장 (INSERT)
     */
    RefreshToken save(RefreshToken refreshToken);

    /**
     * Token Hash로 조회 (폐기/만료 여부 무관)
     * 재사용 감지에 필수
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 단일 토큰 폐기 (logout, 관리자 강제 폐기)
     * 원자적 UPDATE
     */
    int revokeByTokenHash(String tokenHash, LocalDateTime revokedAt);

    /**
     * 특정 tenant의 모든 활성 토큰 폐기 (재사용 공격 감지 시)
     */
    int revokeAllByTenantId(Long tenantId, LocalDateTime revokedAt);

    /**
     * 만료된 토큰 영구 삭제 (배치)
     */
    int deleteByExpiresAtBefore(LocalDateTime before);
}
