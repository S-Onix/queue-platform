package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.tenant.PasswordHasher;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;

    public TenantService(TenantRepository tenantRepository, PasswordHasher passwordHasher, JwtProvider jwtProvider) {
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
        this.jwtProvider = jwtProvider;
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

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Tenant tenant = tenantRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));

        if(!passwordHasher.matches(request.getPassword(), tenant.getPasswordHash())){
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        String accessToken = jwtProvider.generateAccessToken(tenant.getId(), tenant.getTenantId());
        String refreshToken = jwtProvider.generateRefreshToken(tenant.getId());

        return LoginResponse.of(accessToken, refreshToken);
    }

    @Transactional(readOnly = true)
    public RefreshResponse refresh(RefreshRequest request) {
        String token = request.getToken();
        // 1. refreshToken 검증 → 실패 시 예외
        if(!jwtProvider.validateToken(token)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        // 2. Claims에서 id 추출
        Long id = Long.parseLong(jwtProvider.getClaims(token).getSubject());
        // 3. tenantRepository.findById(id) → 존재 확인
        Tenant tenant = tenantRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        // 4. 새 accessToken 생성 (tenantId 필요하니까 Tenant 조회)
        String accessToken = jwtProvider.generateAccessToken(tenant.getId(), tenant.getTenantId());
        String refreshToken = jwtProvider.generateRefreshToken(tenant.getId());
        // 5. RefreshResponse 반환
        return RefreshResponse.of(accessToken, refreshToken);
    }
}
