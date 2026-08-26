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

        Tenant saved = tenantRepository.save(tenant);
        return TenantResponse.from(saved);
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 🔴 두 실패를 같은 코드로 답한다 (계정 열거 차단).
        //
        // 예전엔 없는 이메일이 TENANT_NOT_FOUND(404), 틀린 비밀번호가 INVALID_PASSWORD(401)라
        // **응답 코드 한 번으로 계정 존재 여부가 새어나갔다.** 공격자는 그걸로 실존 계정 목록을
        // 만든 뒤 10/분 예산을 그쪽에만 쓴다.
        //
        // ⚠️ 타이밍 차이는 남는다 — 없는 이메일은 bcrypt를 안 타서 더 빨리 돌아온다.
        //    더미 해시 비교로 시간을 맞추는 방법이 있지만 **하지 않는다**: 쓰레기 이메일마다
        //    bcrypt(cost 10, 약 60ms)를 태우는 CPU 증폭기가 되고, 그 코어는 폴링과 공유한다.
        //    누출을 하나 막으려다 자원 고갈 경로를 여는 맞교환이라 이득이 없다(2026-08-26 판정).
        Tenant tenant = tenantRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if(!passwordHasher.matches(request.getPassword(), tenant.getPasswordHash())){
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
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
