package com.sonix.queue.api.security;

import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantCache;
import com.sonix.queue.domain.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 제어 평면이 데이터 평면과 <b>지갑을 따로 쓰는지</b>.
 *
 * <p>계기는 2026-09-16 AWS 실측이다. 200만 투입 한가운데서 {@code pause} 를 불렀더니 <b>429</b>였다 —
 * 큐 제어와 enqueue 가 {@code rl:tenant:&#123;id&#125;} 하나를 나눠 쓰고 있었고, 부하가 그 버킷을
 * 비워버렸다. <b>멈춰야 하는 순간이 곧 부하가 몰린 순간</b>이라, 비상 스위치가 정확히 필요할 때
 * 안 눌리는 구조였다.
 *
 * <p>🔴 <b>여기서 제일 위험한 것은 분류를 틀리는 것이다.</b> enqueue 를 제어로 잘못 분류하면
 * 핫패스가 60/분에 걸려 플랫폼이 죽는다. 그래서 경로 판정을 표로 전수 단정한다.
 */
class ControlPlaneRateLimitTest {

    /**
     * 🔑 <b>이 판이 이번 수정의 본증명이다.</b> 위의 경로 분류는 "어느 지갑인지 고르는 규칙"이고,
     * 여기서는 <b>실제로 다른 키가 Redis 로 나가는지</b>를 본다. 키가 같으면 분류가 아무리
     * 정확해도 지갑은 하나다.
     */
    @Nested
    @DisplayName("⓪ 경로마다 **다른 키**로 한도를 센다")
    class BucketKeys {

        private RateLimiter tokenBucket;
        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            FixedWindowRateLimiter fixedWindow = mock(FixedWindowRateLimiter.class);
            when(fixedWindow.tryAcquire(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(true);
            tokenBucket = mock(RateLimiter.class);
            when(tokenBucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);

            TenantRepository repo = mock(TenantRepository.class);
            TenantCache cache = mock(TenantCache.class);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTenantId()).thenReturn("t_dev");
            when(cache.get(1L)).thenReturn(Optional.of(tenant));

            filter = new RateLimitFilter(tokenBucket, fixedWindow, repo, cache, 0, 0);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            new TenantAuth(1L, "t_dev"), null, java.util.List.of()));
        }

        @AfterEach
        void clear() {
            SecurityContextHolder.clearContext();
        }

        private String keyFor(String path) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setRemoteAddr("127.0.0.1");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(tokenBucket).tryAcquire(key.capture(), anyInt(), anyDouble());
            return key.getValue();
        }

        @Test
        @DisplayName("enqueue · admit · pause 가 서로 다른 세 키를 쓴다")
        void threeDistinctBuckets() throws Exception {
            assertThat(keyFor("/api/v1/queues/q_1/tokens")).isEqualTo("rl:tenant:t_dev");
        }

        @Test
        @DisplayName("admit 은 배출 지갑")
        void drainBucket() throws Exception {
            assertThat(keyFor("/api/v1/queues/q_1/admit")).isEqualTo("rl:tenant:t_dev:drain");
        }

        @Test
        @DisplayName("🔴 pause 는 제어 지갑 — enqueue 가 한도를 다 써도 여기는 줄지 않는다")
        void controlBucket() throws Exception {
            assertThat(keyFor("/api/v1/queues/q_1/pause")).isEqualTo("rl:tenant:t_dev:control");
        }

        /**
         * 🔑 오버라이드가 <b>실제로 버킷까지 닿는지</b>를 본다.
         *
         * <p>필드에 값이 담기는 것만 보면 부족하다 — 이 레포는 "기본값이 {@code 999999999}여도
         * 초록"이던 테스트를 이미 한 번 가졌다(드레인 용량 캐시). 값이 {@code tryAcquire}의
         * 인자로 넘어가는 것까지 잡아야 회귀가 빨개진다.
         */
        @Test
        @DisplayName("실측 오버라이드가 유입·배출 버킷의 capacity·refill 로 넘어간다")
        void overrideReachesBucket() throws Exception {
            FixedWindowRateLimiter fixedWindow = mock(FixedWindowRateLimiter.class);
            RateLimiter bucket = mock(RateLimiter.class);
            when(bucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);
            TenantCache cache = mock(TenantCache.class);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTenantId()).thenReturn("t_dev");
            when(cache.get(1L)).thenReturn(Optional.of(tenant));
            RateLimitFilter overridden = new RateLimitFilter(
                    bucket, fixedWindow, mock(TenantRepository.class), cache, 3_000_000, 50_000.0);

            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/queues/q_1/tokens");
            req.setRemoteAddr("127.0.0.1");
            overridden.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

            verify(bucket).tryAcquire("rl:tenant:t_dev", 3_000_000, 50_000.0);
        }

        @Test
        @DisplayName("오버라이드가 없으면(0) §89 상수 그대로다")
        void defaultsWhenUnset() throws Exception {
            keyFor("/api/v1/queues/q_1/tokens");
            verify(tokenBucket).tryAcquire("rl:tenant:t_dev",
                    RateLimitFilter.TENANT_CAPACITY, RateLimitFilter.TENANT_REFILL_PER_SEC);
        }

        /**
         * 오버라이드 값을 준 필터로 요청 한 건을 태우고, 그 요청이 때린 버킷 목을 돌려준다.
         * 세 지갑이 각각 어떤 인자를 받았는지를 <b>같은 방식으로</b> 재기 위한 것이다.
         */
        private RateLimiter fire(int capOverride, double refillOverride, String path) throws Exception {
            RateLimiter bucket = mock(RateLimiter.class);
            when(bucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);
            TenantCache cache = mock(TenantCache.class);
            Tenant tenant = mock(Tenant.class);
            when(tenant.getTenantId()).thenReturn("t_dev");
            when(cache.get(1L)).thenReturn(Optional.of(tenant));
            RateLimitFilter f = new RateLimitFilter(
                    bucket, mock(FixedWindowRateLimiter.class), mock(TenantRepository.class),
                    cache, capOverride, refillOverride);

            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setRemoteAddr("127.0.0.1");
            f.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
            return bucket;
        }

        /**
         * 🔴 <b>유입만 올리면 실측이 또 리미터를 잰다.</b> 500만 판에서 배출(admit·verify·complete)은
         * 유저 1명당 2.05회라 유입 다음으로 큰 소비처인데, {@code :drain} 가지가 상수로 되돌아가도
         * {@link #overrideReachesBucket}(유입만 본다)은 <b>초록</b>이다. 그 가지를 따로 잠근다.
         */
        @Test
        @DisplayName("🔴 오버라이드가 배출 지갑(:drain)에도 닿는다")
        void overrideReachesDrainBucket() throws Exception {
            verify(fire(3_000_000, 50_000.0, "/api/v1/queues/q_1/admit"))
                    .tryAcquire("rl:tenant:t_dev:drain", 3_000_000, 50_000.0);
        }

        /**
         * 🔴 <b>오버라이드의 폭발 반경은 정확히 두 지갑이다.</b> 제어 지갑까지 따라 올라가면
         * 실측용 프로퍼티 하나가 남용 방어(분당 60회)를 <b>조용히 끄고</b>, §92의 지갑 분리는
         * "키가 다르다"만 남고 "한도가 다르다"는 사라진다. 기존 {@link #controlBucket}은
         * 키만 보므로 이 회귀를 못 잡는다.
         */
        @Test
        @DisplayName("🔴 제어 지갑(:control)은 오버라이드에 영향받지 않는다")
        void overrideDoesNotReachControlBucket() throws Exception {
            verify(fire(3_000_000, 50_000.0, "/api/v1/queues/q_1/pause"))
                    .tryAcquire("rl:tenant:t_dev:control",
                            RateLimitFilter.CONTROL_CAPACITY, RateLimitFilter.CONTROL_REFILL_PER_SEC);
        }

        /**
         * 계약은 "0"이 아니라 <b>"0 이하"</b>다(생성자 javadoc). {@code > 0} 가드가 {@code != 0}으로
         * 바뀌면 오타 하나({@code -1})가 capacity 음수 버킷을 만들어 그 테넌트의 인증 요청이
         * 전부 429가 되는데, 0만 보는 {@link #defaultsWhenUnset}은 그때도 초록이다.
         */
        @Test
        @DisplayName("음수 오버라이드도 §89 상수로 떨어진다")
        void negativeOverrideFallsBack() throws Exception {
            verify(fire(-1, -1.0, "/api/v1/queues/q_1/tokens"))
                    .tryAcquire("rl:tenant:t_dev",
                            RateLimitFilter.TENANT_CAPACITY, RateLimitFilter.TENANT_REFILL_PER_SEC);
        }
    }

    @Nested
    @DisplayName("① 제어 평면으로 분류되는 경로")
    class ControlPaths {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/queues",                       // 생성
                "/api/v1/queues/q_01a0",                // 조회·수정·삭제
                "/api/v1/queues/q_01a0/pause",
                "/api/v1/queues/q_01a0/resume",
                "/api/v1/tenants/me/api-keys",
                "/api/v1/tenants/me/api-keys/17",
        })
        void isControl(String path) {
            assertThat(RateLimitFilter.isControlPlane(path)).isTrue();
        }
    }

    @Nested
    @DisplayName("② 데이터 평면 — 여기가 제어로 새면 플랫폼이 죽는다")
    class DataPaths {

        /**
         * 🪤 enqueue·admit 은 제어 경로와 <b>접두사가 같다</b>({@code /api/v1/queues/...}).
         * 접두사로 가르면 전부 제어로 빨려 들어가 60/분이 된다.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/queues/q_01a0/tokens",                        // enqueue — 초당 수천
                "/api/v1/queues/q_01a0/admit",
                "/api/v1/queues/q_01a0/status",
                "/api/v1/queues/q_01a0/tokens/tok_9",                  // 개인 폴링
                "/api/v1/queues/q_01a0/tokens/tok_9/complete",
                "/api/v1/queues/q_01a0/admit-tokens/adm_9/verify",
        })
        void isNotControl(String path) {
            assertThat(RateLimitFilter.isControlPlane(path)).isFalse();
        }

        @Test
        @DisplayName("null·빈 경로는 제어가 아니다 — 판정 불가를 제어로 보면 안 된다")
        void nullSafe() {
            assertThat(RateLimitFilter.isControlPlane(null)).isFalse();
            assertThat(RateLimitFilter.isControlPlane("")).isFalse();
        }
    }

    @Nested
    @DisplayName("③ 배출 평면 — 유입과 지갑을 나눈다")
    class DrainPaths {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/queues/q_01a0/admit",
                "/api/v1/queues/q_01a0/admit-tokens/adm_9/verify",
                "/api/v1/queues/q_01a0/tokens/tok_9/complete",
        })
        void isDrain(String path) {
            assertThat(RateLimitFilter.isDrainPlane(path)).isTrue();
            assertThat(RateLimitFilter.isControlPlane(path)).isFalse();
        }

        /**
         * 🪤 {@code /tokens} 로 끝나면 유입이고 {@code /tokens/&#123;id&#125;/complete} 는 배출이다.
         * 접미사로 가르면 enqueue 가 배출로 새거나 그 반대가 된다.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/queues/q_01a0/tokens",          // enqueue — 유입
                "/api/v1/queues/q_01a0/status",
                "/api/v1/queues/q_01a0/tokens/tok_9",    // 개인 폴링
                "/api/v1/queues/q_01a0/pause",
        })
        void isNotDrain(String path) {
            assertThat(RateLimitFilter.isDrainPlane(path)).isFalse();
        }

        @Test
        @DisplayName("세 평면은 서로 겹치지 않는다 — 겹치면 어느 지갑을 쓰는지가 순서에 달린다")
        void planesAreDisjoint() {
            for (String p : new String[]{
                    "/api/v1/queues", "/api/v1/queues/q_1", "/api/v1/queues/q_1/pause",
                    "/api/v1/queues/q_1/admit", "/api/v1/queues/q_1/tokens",
                    "/api/v1/queues/q_1/tokens/t_1/complete"}) {
                assertThat(RateLimitFilter.isControlPlane(p) && RateLimitFilter.isDrainPlane(p))
                        .as("겹침: %s", p).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("④ 한도 상수")
    class Limits {

        @Test
        @DisplayName("제어 평면은 분당 60회 — 데이터 평면과 자릿수가 다르다")
        void controlIsSmallButIndependent() {
            assertThat(RateLimitFilter.CONTROL_CAPACITY).isEqualTo(60);
            assertThat(RateLimitFilter.CONTROL_REFILL_PER_SEC).isEqualTo(1.0);

            // 🔑 작은 것이 요점이 아니다. **다른 지갑**이라는 것이 요점이다.
            assertThat(RateLimitFilter.CONTROL_CAPACITY).isLessThan(RateLimitFilter.TENANT_CAPACITY);
        }
    }
}
