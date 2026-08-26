package com.sonix.queue.api.security;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtProvider 테스트")
class JwtProviderTest {

    private static final Long TENANT_PK = 1L;
    private static final String TENANT_ID = "t_abc123";
    private static final String ACTIVE_KID = "key-test-01";
    private static final String PREVIOUS_KID = "key-test-00";
    private static final String ACTIVE_SECRET = "test-current-secret-must-be-32-bytes-or-more-aaa";
    private static final String PREVIOUS_SECRET = "test-previous-secret-must-be-32-bytes-or-more-bb";

    private JwtKeyStore keyStore;
    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        // JwtKeyStore 직접 구성 (Spring 없이)
        keyStore = new JwtKeyStore();
        keyStore.setActiveKid(ACTIVE_KID);
        keyStore.setKeys(buildKeyConfigs());

        // JwtProperties 직접 구성 (Spring 없이)
        JwtProperties jwtProperties = new JwtProperties(
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );

        // JwtProvider 생성
        jwtProvider = new JwtProvider(keyStore, jwtProperties);
    }

    private List<JwtKeyStore.KeyConfig> buildKeyConfigs() {
        List<JwtKeyStore.KeyConfig> list = new ArrayList<>();

        JwtKeyStore.KeyConfig active = new JwtKeyStore.KeyConfig();
        active.setKid(ACTIVE_KID);
        active.setSecret(ACTIVE_SECRET);
        list.add(active);

        JwtKeyStore.KeyConfig previous = new JwtKeyStore.KeyConfig();
        previous.setKid(PREVIOUS_KID);
        previous.setSecret(PREVIOUS_SECRET);
        list.add(previous);

        return list;
    }

    // ==========================================
    // 발급
    // ==========================================

    @Nested
    @DisplayName("토큰 발급")
    class Generate {

        @Test
        @DisplayName("Access Token 발급 시 kid + type=ACCESS 포함")
        void generateAccessToken_includesKidAndType() {
            String token = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);

            assertNotNull(token);
            assertTrue(token.contains(".") && token.split("\\.").length == 3);

            // 발급된 토큰을 그대로 검증 → 통과 기대
            Claims claims = jwtProvider.parseAndValidateAccess(token);
            assertEquals(TENANT_PK.toString(), claims.getSubject());
            assertEquals(TENANT_ID, claims.get("tenantId", String.class));
            assertEquals("ACCESS", claims.get("type", String.class));
        }

        @Test
        @DisplayName("Refresh Token 발급 시 kid + type=REFRESH 포함")
        void generateRefreshToken_includesKidAndType() {
            String token = jwtProvider.generateRefreshToken(TENANT_PK, TENANT_ID);

            assertNotNull(token);

            Claims claims = jwtProvider.parseAndValidateRefresh(token);
            assertEquals(TENANT_PK.toString(), claims.getSubject());
            assertEquals(TENANT_ID, claims.get("tenantId", String.class));
            assertEquals("REFRESH", claims.get("type", String.class));
        }
    }

    // ==========================================
    // type 클레임 검증 (Phase A 핵심)
    // ==========================================

    @Nested
    @DisplayName("type 클레임 강제 검증")
    class TypeValidation {

        @Test
        @DisplayName("Refresh Token으로 parseAndValidateAccess 호출 → INVALID_TOKEN")
        void refreshToken_failsAsAccess() {
            String refreshToken = jwtProvider.generateRefreshToken(TENANT_PK, TENANT_ID);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateAccess(refreshToken));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("Access Token으로 parseAndValidateRefresh 호출 → INVALID_TOKEN")
        void accessToken_failsAsRefresh() {
            String accessToken = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateRefresh(accessToken));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }
    }

    // ==========================================
    // 잘못된 토큰
    // ==========================================

    @Nested
    @DisplayName("잘못된 토큰 처리")
    class InvalidToken {

        @Test
        @DisplayName("잘못된 형식 → INVALID_TOKEN")
        void malformed() {
            assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateAccess("not-a-jwt"));
        }

        @Test
        @DisplayName("점이 2개 미만 → INVALID_TOKEN")
        void notEnoughParts() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateAccess("only.two"));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("서명 위조 → INVALID_TOKEN")
        void tamperedSignature() {
            String token = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);
            String tampered = token.substring(0, token.length() - 5) + "XXXXX";

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateAccess(tampered));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }
    }

    // ==========================================
    // Key Rotation
    // ==========================================

    @Nested
    @DisplayName("Key Rotation 시나리오")
    class KeyRotation {

        @Test
        @DisplayName("Active 키로 발급한 토큰은 검증 통과")
        void activeKeyToken_isValid() {
            String token = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);

            assertDoesNotThrow(() -> jwtProvider.parseAndValidateAccess(token));
        }

        @Test
        @DisplayName("Active 전환 후에도 옛 키로 발급한 토큰은 검증 가능")
        void previousKeyToken_isStillValid() {
            // 1. 현재 active=key-test-01로 토큰 발급
            String oldToken = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);

            // 2. Rotation: active를 key-test-00으로 전환 (가상 시나리오)
            //    실제로는 yml 변경 + 재배포지만, 테스트에선 직접 변경
            keyStore.setActiveKid(PREVIOUS_KID);
            // keys 리스트는 그대로 (두 키 모두 등록됨)

            // 3. 옛 active 키 (key-test-01)로 발급한 토큰도 여전히 검증 가능
            assertDoesNotThrow(() -> jwtProvider.parseAndValidateAccess(oldToken));
        }

        @Test
        @DisplayName("keys에서 제거된 kid로 발급한 토큰은 INVALID_TOKEN")
        void removedKey_failsValidation() {
            // 1. 현재 키로 토큰 발급
            String token = jwtProvider.generateAccessToken(TENANT_PK, TENANT_ID);

            // 2. 그 키를 keys 리스트에서 제거 (active key 폐기 시나리오)
            keyStore.setKeys(List.of()); // 모든 키 제거

            // 3. 토큰의 kid가 keyStore에 없음 → INVALID_TOKEN
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> jwtProvider.parseAndValidateAccess(token));

            assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }
    }

    // ==========================================
    // JwtKeyStore 자체 동작
    // ==========================================

    @Nested
    @DisplayName("JwtKeyStore 동작")
    class KeyStoreBehavior {

        @Test
        @DisplayName("getActiveKid 반환")
        void getActiveKid() {
            assertEquals(ACTIVE_KID, keyStore.getActiveKid());
        }

        @Test
        @DisplayName("getActiveKey 반환 - active key 존재")
        void getActiveKey_returnsKey() {
            assertNotNull(keyStore.getActiveKey());
        }

        @Test
        @DisplayName("findKey - 등록된 kid")
        void findKey_existing() {
            assertTrue(keyStore.findKey(ACTIVE_KID).isPresent());
            assertTrue(keyStore.findKey(PREVIOUS_KID).isPresent());
        }

        @Test
        @DisplayName("findKey - 등록 안 된 kid")
        void findKey_unknown() {
            assertTrue(keyStore.findKey("unknown-kid").isEmpty());
        }

        @Test
        @DisplayName("findKey - null")
        void findKey_null() {
            assertTrue(keyStore.findKey(null).isEmpty());
        }

        @Test
        @DisplayName("getAllKids - 등록된 모든 kid 반환")
        void getAllKids() {
            List<String> kids = keyStore.getAllKids();
            assertEquals(2, kids.size());
            assertTrue(kids.contains(ACTIVE_KID));
            assertTrue(kids.contains(PREVIOUS_KID));
        }

        @Test
        @DisplayName("active key 없으면 getActiveKey 호출 시 IllegalStateException")
        void getActiveKey_noActiveKey() {
            JwtKeyStore emptyStore = new JwtKeyStore();
            emptyStore.setActiveKid("nonexistent");
            emptyStore.setKeys(List.of());

            assertThrows(IllegalStateException.class, emptyStore::getActiveKey);
        }

        @Test
        @DisplayName("blank kid는 keys 등록에서 무시됨")
        void setKeys_ignoresBlankKid() {
            JwtKeyStore store = new JwtKeyStore();

            JwtKeyStore.KeyConfig validConfig = new JwtKeyStore.KeyConfig();
            validConfig.setKid("valid-kid");
            validConfig.setSecret(ACTIVE_SECRET);

            JwtKeyStore.KeyConfig blankConfig = new JwtKeyStore.KeyConfig();
            blankConfig.setKid("");  // blank
            blankConfig.setSecret(ACTIVE_SECRET);

            store.setKeys(List.of(validConfig, blankConfig));

            assertEquals(1, store.getAllKids().size());
            assertTrue(store.findKey("valid-kid").isPresent());
        }
    }

    /**
     * 🔴 <b>같은 초에 두 번 발급해도 토큰이 달라야 한다.</b>
     *
     * <p>{@code jti}가 없던 시절엔 {@code sub}·{@code tenantId}·{@code type}·{@code iat}·{@code exp}가
     * 전부 같고 {@code iat}가 <b>초 단위</b>라 두 발급이 <b>바이트 단위로 동일</b>했다.
     * 그러면 {@code sha256}도 같아 {@code refresh_tokens.token_hash} UNIQUE에 걸려 <b>500</b>이 났다 —
     * 로그인 더블클릭, 그리고 {@code refresh()}가 폐기 직후 재발급해 <b>자기가 방금 지운 행과 충돌</b>하는 경로.
     *
     * <p><b>왜 통합 테스트가 아니라 여기인가</b>: 결함은 이 함수의 <b>값 생성</b>에 있다.
     * MySQL을 띄워 "같은 초 로그인 2회"를 재현하면 같은 명제를 훨씬 비싸게 검증하고,
     * 초 경계에 걸리면 플레이키가 된다. 두 번 불러 다르면 그것으로 끝난다.
     */
    @Test
    @DisplayName("generateRefreshToken은 같은 입력·같은 초에도 매번 다른 토큰을 만든다 (jti)")
    void refreshTokenIsUniquePerIssue() {
        String first = jwtProvider.generateRefreshToken(1L, "t_dev");
        String second = jwtProvider.generateRefreshToken(1L, "t_dev");

        assertNotEquals(first, second);
    }
}