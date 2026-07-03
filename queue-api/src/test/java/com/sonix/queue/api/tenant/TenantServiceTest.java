package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.JwtProperties;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.auth.RefreshToken;
import com.sonix.queue.domain.auth.RefreshTokenRepository;
import com.sonix.queue.domain.tenant.*;
import io.jsonwebtoken.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantService 테스트")
class TenantServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock PasswordHasher passwordHasher;
    @Mock JwtProvider jwtProvider;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenRevocationService tokenRevocationService;

    TenantService tenantService;

    private final JwtProperties jwtProperties = new JwtProperties(
            Duration.ofMinutes(15),
            Duration.ofDays(7)
    );

    // 공통 데이터
    private static final Long TENANT_PK = 1L;
    private static final String TENANT_ID = "t_abc123";
    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "password123!";
    private static final String PASSWORD_HASH = "hashedPwd";
    private static final String ACCESS_TOKEN = "access-jwt-xxx";
    private static final String REFRESH_TOKEN = "refresh-jwt-xxx";
    private static final String NEW_REFRESH_TOKEN = "new-refresh-jwt-xxx";

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        // ⭐ 실제 Tenant 객체 사용 (Mock 없음 → UnnecessaryStubbingException 회피)
        tenant = Tenant.reconstruct(
                TENANT_PK,
                TENANT_ID,
                EMAIL,
                PASSWORD_HASH,
                "Test Tenant",
                TenantStatus.ACTIVE,
                Plan.FREE,
                LocalDateTime.now()
        );

        // TenantService 수동 구성 (JwtProperties는 record라 실제 인스턴스 주입)
        tenantService = new TenantService(
                tenantRepository,
                passwordHasher,
                jwtProvider,
                jwtProperties,
                refreshTokenRepository,
                tokenRevocationService
        );
    }

    // ==========================================
    // 로그인
    // ==========================================

    @Nested
    @DisplayName("로그인")
    class LoginTests {

        @Test
        @DisplayName("정상 로그인 시 Refresh Token이 DB에 저장된다")
        void login_savesRefreshTokenToDb() {
            // given
            LoginRequest request = new LoginRequest(EMAIL, PASSWORD);
            when(tenantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(tenant));
            when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
            when(jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID)).thenReturn(ACCESS_TOKEN);
            when(jwtProvider.generateRefreshToken(TENANT_PK, TENANT_ID)).thenReturn(REFRESH_TOKEN);
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            LoginResponse response = tenantService.login(request);

            // then
            assertEquals(ACCESS_TOKEN, response.getAccessToken());
            assertEquals(REFRESH_TOKEN, response.getRefreshToken());

            verify(refreshTokenRepository).save(argThat(token ->
                    token.getTenantId().equals(TENANT_PK)
                            && token.matches(REFRESH_TOKEN)
                            && !token.isRevoked()
                            && !token.isExpired()
            ));
        }

        @Test
        @DisplayName("존재하지 않는 이메일 → TENANT_NOT_FOUND")
        void login_throwsWhenEmailNotFound() {
            LoginRequest request = new LoginRequest(EMAIL, PASSWORD);
            when(tenantRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.login(request));

            assertEquals(ErrorCode.TENANT_NOT_FOUND, ex.getErrorCode());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("잘못된 비밀번호 → INVALID_PASSWORD")
        void login_throwsWhenPasswordInvalid() {
            LoginRequest request = new LoginRequest(EMAIL, "wrong-password");
            when(tenantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(tenant));
            when(passwordHasher.matches("wrong-password", PASSWORD_HASH)).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.login(request));

            assertEquals(ErrorCode.INVALID_PASSWORD, ex.getErrorCode());
            verify(refreshTokenRepository, never()).save(any());
        }
    }

    // ==========================================
    // 토큰 갱신
    // ==========================================

    @Nested
    @DisplayName("토큰 갱신")
    class RefreshTests {

        @Test
        @DisplayName("정상 refresh — Token Rotation 동작 검증")
        void refresh_rotatesToken() {
            // given
            RefreshRequest request = new RefreshRequest(REFRESH_TOKEN);
            Claims claims = mockClaims(TENANT_PK.toString());

            when(jwtProvider.parseAndValidateRefresh(REFRESH_TOKEN)).thenReturn(claims);
            when(tenantRepository.findById(TENANT_PK)).thenReturn(Optional.of(tenant));

            // DB에 활성 토큰 존재
            String hash = RefreshToken.sha256(REFRESH_TOKEN);
            RefreshToken storedToken = RefreshToken.create(REFRESH_TOKEN, TENANT_PK, Duration.ofDays(7));
            when(refreshTokenRepository.findByTokenHash(hash))
                    .thenReturn(Optional.of(storedToken));

            when(jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID)).thenReturn(ACCESS_TOKEN);
            when(jwtProvider.generateRefreshToken(TENANT_PK, TENANT_ID)).thenReturn(NEW_REFRESH_TOKEN);
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            RefreshResponse response = tenantService.refresh(request);

            // then
            assertEquals(ACCESS_TOKEN, response.getAccessToken());
            assertEquals(NEW_REFRESH_TOKEN, response.getRefreshToken());

            // 옛 토큰 폐기 검증
            verify(refreshTokenRepository).revokeByTokenHash(eq(hash), any(LocalDateTime.class));

            // 새 토큰 DB 저장 검증
            verify(refreshTokenRepository).save(argThat(token ->
                    token.matches(NEW_REFRESH_TOKEN)
            ));

            // 재사용 감지 안 됨
            verify(tokenRevocationService, never()).revokeAllForTenant(any());
        }

        @Test
        @DisplayName("JWT 검증 실패 → INVALID_TOKEN")
        void refresh_throwsWhenJwtInvalid() {
            RefreshRequest request = new RefreshRequest("invalid-jwt");
            when(jwtProvider.parseAndValidateRefresh("invalid-jwt"))
                    .thenThrow(new RuntimeException("invalid signature"));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.refresh(request));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
            verify(refreshTokenRepository, never()).findByTokenHash(any());
            verify(tokenRevocationService, never()).revokeAllForTenant(any());
        }

        @Test
        @DisplayName("Tenant 존재하지 않음 → TENANT_NOT_FOUND")
        void refresh_throwsWhenTenantNotFound() {
            RefreshRequest request = new RefreshRequest(REFRESH_TOKEN);
            Claims claims = mockClaims(TENANT_PK.toString());

            when(jwtProvider.parseAndValidateRefresh(REFRESH_TOKEN)).thenReturn(claims);
            when(tenantRepository.findById(TENANT_PK)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.refresh(request));

            assertEquals(ErrorCode.TENANT_NOT_FOUND, ex.getErrorCode());
            verify(refreshTokenRepository, never()).findByTokenHash(any());
        }

        @Test
        @DisplayName("재사용 공격 1: DB에 없는 토큰 → 모든 토큰 폐기 + INVALID_TOKEN")
        void refresh_detectsReuseAttack_tokenNotInDb() {
            RefreshRequest request = new RefreshRequest(REFRESH_TOKEN);
            Claims claims = mockClaims(TENANT_PK.toString());

            when(jwtProvider.parseAndValidateRefresh(REFRESH_TOKEN)).thenReturn(claims);
            when(tenantRepository.findById(TENANT_PK)).thenReturn(Optional.of(tenant));
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.refresh(request));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());

            // 핵심 검증: 별도 트랜잭션으로 모든 토큰 폐기
            verify(tokenRevocationService).revokeAllForTenant(TENANT_PK);

            // 새 토큰 발급 안 됨
            verify(jwtProvider, never()).generateAccessToken(any(), any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("재사용 공격 2: 폐기된 토큰 재사용 → 모든 토큰 폐기 + INVALID_TOKEN")
        void refresh_detectsReuseAttack_revokedTokenUsed() {
            RefreshRequest request = new RefreshRequest(REFRESH_TOKEN);
            Claims claims = mockClaims(TENANT_PK.toString());

            when(jwtProvider.parseAndValidateRefresh(REFRESH_TOKEN)).thenReturn(claims);
            when(tenantRepository.findById(TENANT_PK)).thenReturn(Optional.of(tenant));

            // 폐기된 토큰
            RefreshToken revokedToken = RefreshToken.create(REFRESH_TOKEN, TENANT_PK, Duration.ofDays(7));
            revokedToken.revoke();

            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(revokedToken));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.refresh(request));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());

            // 핵심 검증: 도난 신호 → 모든 토큰 폐기
            verify(tokenRevocationService).revokeAllForTenant(TENANT_PK);
        }

        @Test
        @DisplayName("만료된 토큰 → INVALID_TOKEN (재사용 감지 안 함)")
        void refresh_expiredToken_noRevokeAll() {
            RefreshRequest request = new RefreshRequest(REFRESH_TOKEN);
            Claims claims = mockClaims(TENANT_PK.toString());

            when(jwtProvider.parseAndValidateRefresh(REFRESH_TOKEN)).thenReturn(claims);
            when(tenantRepository.findById(TENANT_PK)).thenReturn(Optional.of(tenant));

            // 만료된 토큰 (폐기 아님)
            LocalDateTime past = LocalDateTime.now().minusDays(1);
            RefreshToken expiredToken = RefreshToken.reconstruct(
                    1L, TENANT_PK, RefreshToken.sha256(REFRESH_TOKEN),
                    past.minusDays(7), past, null
            );

            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(expiredToken));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> tenantService.refresh(request));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());

            // 만료는 일반 무효 → revokeAll 호출 안 함 ⭐ 구분 검증
            verify(tokenRevocationService, never()).revokeAllForTenant(any());
        }
    }

    // ==========================================
    // 로그아웃
    // ==========================================

    @Nested
    @DisplayName("로그아웃")
    class LogoutTests {

        @Test
        @DisplayName("정상 로그아웃 → revokeByTokenHash 호출")
        void logout_revokesTokenByHash() {
            tenantService.logout(REFRESH_TOKEN);

            String expectedHash = RefreshToken.sha256(REFRESH_TOKEN);
            verify(refreshTokenRepository).revokeByTokenHash(
                    eq(expectedHash), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("이미 폐기된 토큰의 logout도 예외 없음 (멱등성)")
        void logout_idempotent() {
            // 영향받은 row 0건이어도 예외 없음
            when(refreshTokenRepository.revokeByTokenHash(anyString(), any(LocalDateTime.class)))
                    .thenReturn(0);

            assertDoesNotThrow(() -> tenantService.logout(REFRESH_TOKEN));
        }
    }

    // ==========================================
    // 헬퍼
    // ==========================================

    private Claims mockClaims(String subject) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(subject);
        return claims;
    }
}