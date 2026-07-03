package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.JwtProperties;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.auth.RefreshToken;
import com.sonix.queue.domain.auth.RefreshTokenRepository;
import com.sonix.queue.domain.tenant.PasswordHasher;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final TokenRevocationService tokenRevocationService;

    public TenantService(TenantRepository tenantRepository, PasswordHasher passwordHasher,
                         JwtProvider jwtProvider, JwtProperties jwtProperties,
                         RefreshTokenRepository refreshTokenRepository,
                         TokenRevocationService tokenRevocationService) {
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Transactional
    public TenantResponse signup(SignupRequest request) {
        boolean isExist = tenantRepository.existsByEmail(request.getEmail());
        if (isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String hash = passwordHasher.hash(request.getPassword());
        Tenant tenant = Tenant.create(request.getEmail(), hash, request.getName());

        return TenantResponse.from(tenant);
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Tenant tenant = tenantRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));

        if(!passwordHasher.matches(request.getPassword(), tenant.getPasswordHash())){
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        String accessToken = jwtProvider.generateAccessToken(tenant.getId(), tenant.getTenantId());
        String refreshToken = jwtProvider.generateRefreshToken(tenant.getId(), tenant.getTenantId());

        RefreshToken refreshTokenDomain = RefreshToken.create(
                refreshToken,
                tenant.getId(),
                jwtProperties.refreshTokenExpiry()
        );

        refreshTokenRepository.save(refreshTokenDomain);

        return LoginResponse.of(accessToken, refreshToken);
    }

    @Transactional
    public RefreshResponse refresh(RefreshRequest request) {
        String refreshToken = request.getToken();

        // 1. JWT 검증 + type=REFRESH 강제 (Access Token 차단)
        Claims claims;
        try {
            claims = jwtProvider.parseAndValidateRefresh(refreshToken);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 2. Claims에서 id 추출
        Long id = Long.parseLong(claims.getSubject());

        // 3. Tenant 존재 확인
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));

        // 4. DB에서 Refresh Token 조회 (재사용 감지)
        String hash = RefreshToken.sha256(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> {
                    // 토큰이 DB에 없으면 재사용 공격 의심 (별도의 Service를 호출하여 Transaction을 분리함 Exception으로 인한 Rollback을 대비)
                   tokenRevocationService.revokeAllForTenant(tenant.getId());
                   return new BusinessException(ErrorCode.INVALID_TOKEN);
                });

        // 5. 사용 가능 여부 확인
        if (!stored.isUsable()) {
            // 폐기 여부 확인
            if(stored.isRevoked()){
                // 폐기된 토큰 재사용 = 도난 당한 것 -> 해당 tenant의 모든 토큰 폐기
                tokenRevocationService.revokeAllForTenant(tenant.getId());
            }
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 6. 이전 토큰 폐기
        refreshTokenRepository.revokeByTokenHash(hash, LocalDateTime.now());

        // 7. 새 토큰 발급
        String accessToken = jwtProvider.generateAccessToken(tenant.getId(), tenant.getTenantId());
        String newRefreshToken = jwtProvider.generateRefreshToken(tenant.getId(), tenant.getTenantId());

        // 8. 새 Refresh Token DB 저장
        RefreshToken refreshTokenDomain = RefreshToken.create(
                newRefreshToken,
                tenant.getId(),
                jwtProperties.refreshTokenExpiry()
        );

        refreshTokenRepository.save(refreshTokenDomain);

        return RefreshResponse.of(accessToken, newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        String hash = RefreshToken.sha256(refreshToken);
        refreshTokenRepository.revokeByTokenHash(hash, LocalDateTime.now());
    }
}
