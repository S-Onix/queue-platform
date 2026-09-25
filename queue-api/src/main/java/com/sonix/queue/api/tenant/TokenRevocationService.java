package com.sonix.queue.api.tenant;

import com.sonix.queue.domain.auth.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 이유: Refresh Token 폐기 전용 서비스 — {@code REQUIRES_NEW} 로 별도 트랜잭션에서 커밋한다.
 * 문제: 재사용 공격을 감지한 요청은 예외로 끝나 호출 측 트랜잭션이 롤백된다.
 * 해결: 폐기만 따로 커밋해 롤백과 무관하게 남긴다. 호출처는 refresh 의 재사용 감지 두 곳뿐이다(TenantService).
 *
 * @author sonix
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
