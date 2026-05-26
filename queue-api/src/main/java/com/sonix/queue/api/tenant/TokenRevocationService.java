package com.sonix.queue.api.tenant;

import com.sonix.queue.domain.auth.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Refresh Token 폐기 전용 서비스
 *
 * @Transactional(propagation = REQUIRES_NEW)로 별도 트랜잭션에서 실행.
 * 호출 측 트랜잭션이 ROLLBACK되어도 폐기는 영구 커밋됨.
 *
 * 사용 시점:
 *   - 재사용 공격 감지 시 (refresh() 안에서 호출)
 *   - 관리자 강제 폐기 (운영 도구)
 *   - 비밀번호 변경 시 (선택)
 */
@Service
public class TokenRevocationService {
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForTenant(Long tenantId) {
        return refreshTokenRepository.revokeAllByTenantId(tenantId, LocalDateTime.now());
    }
}
