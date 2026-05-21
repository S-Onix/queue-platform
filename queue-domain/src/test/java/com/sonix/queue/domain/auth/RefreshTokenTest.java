package com.sonix.queue.domain.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RefreshToken 도메인 모델 테스트")
class RefreshTokenTest {
    private static final String RAW_TOKEN = "sample-jwt-token-abc123";
    private static final Long TENANT_ID = 1L;
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    @Nested
    @DisplayName("RefreshToken 생성")
    class Create {

        @Test
        @DisplayName("create()는 원본 토큰을 SHA-256 hash로 저장한다")
        void create_storesTokenAsHash() {
            // when
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);

            // then
            assertNotEquals(RAW_TOKEN, token.getTokenHash());   // 원본 그대로 아님
            assertEquals(64, token.getTokenHash().length());     // SHA-256 hex = 64자
        }

        @Test
        @DisplayName("create() 시 id는 null이다 (DB 저장 전)")
        void create_idIsNullBeforePersist() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertNull(token.getId());
        }

        @Test
        @DisplayName("create() 시 revokedAt은 null이다 (발급 직후)")
        void create_revokedAtIsNull() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertNull(token.getRevokedAt());
        }

        @Test
        @DisplayName("create() 시 expiresAt = issuedAt + ttl")
        void create_setsExpiresAtCorrectly() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);

            Duration actual = Duration.between(token.getIssuedAt(), token.getExpiresAt());
            assertEquals(DEFAULT_TTL, actual);
        }

        @Test
        @DisplayName("create() 시 rawToken null이면 NullPointerException")
        void create_throwsWhenRawTokenIsNull() {
            assertThrows(NullPointerException.class,
                    () -> RefreshToken.create(null, TENANT_ID, DEFAULT_TTL));
        }

        @Test
        @DisplayName("create() 시 tenantId null이면 NullPointerException")
        void create_throwsWhenTenantIdIsNull() {
            assertThrows(NullPointerException.class,
                    () -> RefreshToken.create(RAW_TOKEN, null, DEFAULT_TTL));
        }

        @Test
        @DisplayName("create() 시 ttl null이면 NullPointerException")
        void create_throwsWhenTtlIsNull() {
            assertThrows(NullPointerException.class,
                    () -> RefreshToken.create(RAW_TOKEN, TENANT_ID, null));
        }
    }

    // ==========================================
    // B. 폐기 (revoke)
    // ==========================================

    @Nested
    @DisplayName("토큰 폐기 (revoke)")
    class Revoke {

        @Test
        @DisplayName("revoke() 호출 시 revokedAt이 설정된다")
        void revoke_setsRevokedAt() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertNull(token.getRevokedAt());

            token.revoke();

            assertNotNull(token.getRevokedAt());
            assertTrue(token.isRevoked());
        }

        @Test
        @DisplayName("revoke()를 두 번 호출해도 첫 시점이 유지된다 (멱등성)")
        void revoke_isIdempotent() throws InterruptedException {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);

            token.revoke();
            LocalDateTime firstRevokedAt = token.getRevokedAt();

            Thread.sleep(10);   // 시간 차이 만들기
            token.revoke();
            LocalDateTime secondRevokedAt = token.getRevokedAt();

            assertEquals(firstRevokedAt, secondRevokedAt);
        }

        @Test
        @DisplayName("폐기 전 isRevoked()는 false")
        void isRevoked_beforeRevokeReturnsFalse() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertFalse(token.isRevoked());
        }
    }

    // ==========================================
    // C. 만료 (isExpired)
    // ==========================================

    @Nested
    @DisplayName("토큰 만료 확인")
    class Expiry {

        @Test
        @DisplayName("미래 만료 토큰은 isExpired() false")
        void notExpiredYet() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertFalse(token.isExpired());
        }

        @Test
        @DisplayName("과거 만료 토큰은 isExpired() true")
        void alreadyExpired() {
            LocalDateTime past = LocalDateTime.now().minusDays(1);

            // 과거 만료 토큰 시뮬레이션 (reconstruct로 직접 만료시킨 상태 만들기)
            RefreshToken token = RefreshToken.reconstruct(
                    1L,
                    TENANT_ID,
                    RefreshToken.sha256(RAW_TOKEN),
                    past.minusDays(7),
                    past,                   // 어제 만료
                    null
            );

            assertTrue(token.isExpired());
        }
    }

    // ==========================================
    // D. 사용 가능 여부 (isUsable)
    // ==========================================

    @Nested
    @DisplayName("토큰 사용 가능 여부 (isUsable)")
    class Usable {

        @Test
        @DisplayName("정상 토큰 (미만료 + 미폐기)은 isUsable() true")
        void normalTokenIsUsable() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertTrue(token.isUsable());
        }

        @Test
        @DisplayName("폐기된 토큰은 isUsable() false")
        void revokedTokenIsNotUsable() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            token.revoke();

            assertFalse(token.isUsable());
        }

        @Test
        @DisplayName("만료된 토큰은 isUsable() false")
        void expiredTokenIsNotUsable() {
            LocalDateTime past = LocalDateTime.now().minusDays(1);
            RefreshToken token = RefreshToken.reconstruct(
                    1L, TENANT_ID, RefreshToken.sha256(RAW_TOKEN),
                    past.minusDays(7), past, null
            );

            assertFalse(token.isUsable());
        }

        @Test
        @DisplayName("만료 + 폐기된 토큰은 isUsable() false")
        void expiredAndRevokedIsNotUsable() {
            LocalDateTime past = LocalDateTime.now().minusDays(1);
            RefreshToken token = RefreshToken.reconstruct(
                    1L, TENANT_ID, RefreshToken.sha256(RAW_TOKEN),
                    past.minusDays(7), past, past
            );

            assertFalse(token.isUsable());
            assertTrue(token.isExpired());
            assertTrue(token.isRevoked());
        }
    }

    // ==========================================
    // E. 매칭 (matches)
    // ==========================================

    @Nested
    @DisplayName("토큰 매칭")
    class Matches {

        @Test
        @DisplayName("같은 원본 토큰은 matches() true")
        void sameTokenMatches() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertTrue(token.matches(RAW_TOKEN));
        }

        @Test
        @DisplayName("다른 원본 토큰은 matches() false")
        void differentTokenDoesNotMatch() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            assertFalse(token.matches("different-token-xyz"));
        }

        @Test
        @DisplayName("한 글자만 달라도 matches() false")
        void slightlyDifferentDoesNotMatch() {
            RefreshToken token = RefreshToken.create(RAW_TOKEN, TENANT_ID, DEFAULT_TTL);
            String slightlyDifferent = RAW_TOKEN.substring(0, RAW_TOKEN.length() - 1) + "X";
            assertFalse(token.matches(slightlyDifferent));
        }
    }

    // ==========================================
    // F. SHA-256 검증
    // ==========================================

    @Nested
    @DisplayName("SHA-256 해시 함수")
    class Sha256 {

        @Test
        @DisplayName("같은 입력은 같은 hash를 만든다 (결정적)")
        void deterministic() {
            String input = "test-input-123";

            String hash1 = RefreshToken.sha256(input);
            String hash2 = RefreshToken.sha256(input);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("다른 입력은 다른 hash를 만든다")
        void differentInputsProduceDifferentHashes() {
            String hashA = RefreshToken.sha256("input-A");
            String hashB = RefreshToken.sha256("input-B");

            assertNotEquals(hashA, hashB);
        }

        @Test
        @DisplayName("hash는 항상 64자 (SHA-256 hex)")
        void hashLengthIs64() {
            assertEquals(64, RefreshToken.sha256("short").length());
            assertEquals(64, RefreshToken.sha256("a-very-long-input-that-is-much-longer").length());
            assertEquals(64, RefreshToken.sha256("").length());
        }

        @Test
        @DisplayName("hash는 hex 형식만 포함")
        void hashIsHexOnly() {
            String hash = RefreshToken.sha256("test");
            assertTrue(hash.matches("^[0-9a-f]{64}$"));
        }
    }
}