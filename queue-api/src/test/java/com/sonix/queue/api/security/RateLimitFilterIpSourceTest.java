package com.sonix.queue.api.security;

import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.domain.tenant.TenantCache;
import com.sonix.queue.domain.tenant.TenantRepository;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 인증 전 Rate Limit의 IP 출처가 TCP peer(remoteAddr) 하나로 고정되는지에 대한 회귀 테스트.
 *
 * <p>컨트롤러 테스트들은 전부 {@code @MockBean RateLimitFilter}라 필터 본문이 한 번도 실행되지 않는다.
 * 그래서 누가 XFF를 신뢰하는 extractIp를 되살려도 전체 테스트가 초록이다 — 여기서만 잡힌다.
 * XFF/X-Real-IP/Forwarded는 클라이언트가 임의로 쓰는 값이라, 이걸 키에 넣으면
 * 헤더만 바꿔서 한도를 무한 우회할 수 있다.
 *
 * <p>Spring 컨텍스트 없이 순수 Mockito. doFilterInternal이 protected라 같은 패키지에 둔다.
 */
class RateLimitFilterIpSourceTest {

    private FixedWindowRateLimiter fixedWindow;
    private RateLimiter tokenBucket;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        fixedWindow = mock(FixedWindowRateLimiter.class);
        when(fixedWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        tokenBucket = mock(RateLimiter.class);
        when(tokenBucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);
        filter = new RateLimitFilter(
                tokenBucket, fixedWindow,
                mock(TenantRepository.class), mock(TenantCache.class));
    }

    /** 인증 전 분기를 타야 하므로 SecurityContext는 비운 상태를 보장한다. */
    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 🔴 <b>공개 endpoint는 인증돼 있어도 IP Fixed Window를 탄다.</b>
     *
     * <p>{@code /login}은 {@code permitAll}이고 {@code JwtAuthenticationFilter}는 경로를 안 가리므로,
     * <b>유효한 Access 토큰을 헤더에 붙이면 SecurityContext가 채워진 채로 이 필터에 도달한다.</b>
     * 예전엔 그때 "인증된 요청" 분기로 빠져 {@code rl:tenant:&#123;id&#125;}를 썼다 —
     * 즉 <b>클라이언트가 자기 한도를 고를 수 있었다.</b> LOGIN 10/분 대비 10배이고,
     * 계정을 K개 만들면 버킷도 K개라 IP 기준 한도가 사실상 사라진다.
     *
     * <p>이 케이스가 없으면 그 선처리 블록을 지워도 전체 테스트가 초록이다 —
     * 컨트롤러 테스트는 전부 {@code @MockBean RateLimitFilter}라 필터 본문이 안 돈다.
     */
    @Test
    @DisplayName("인증된 상태로 /login을 쳐도 테넌트 버킷이 아니라 LOGIN IP 윈도우를 탄다")
    void authenticatedRequestOnPublicEndpointStillUsesIpWindow() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TenantAuth(1L, "t_dev"), null, java.util.List.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tenants/login");
        request.setRemoteAddr("203.0.113.9");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixedWindow).tryAcquire(key.capture(), eq(10), eq(60_000L));
        assertThat(key.getValue()).contains("203.0.113.9");
        verifyNoInteractions(tokenBucket);   // ← 테넌트 버킷으로 새면 안 된다
    }

    private void callSignupFrom(String remoteAddr, String... headers) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/tenants/signup");
        req.setRemoteAddr(remoteAddr);
        for (int i = 0; i < headers.length; i += 2) {
            req.addHeader(headers[i], headers[i + 1]);
        }
        filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    @DisplayName("프록시 헤더를 어떻게 조작해도 Rate Limit 키는 TCP peer(1.2.3.4) 하나로 고정된다")
    void proxyHeadersNeverAffectRateLimitKey() throws Exception {
        callSignupFrom("1.2.3.4");                                              // (a) 헤더 없음
        callSignupFrom("1.2.3.4", "X-Forwarded-For", "9.9.9.9");                // (b) 단일 위조
        callSignupFrom("1.2.3.4", "X-Forwarded-For", "9.9.9.9, 8.8.8.8");       // (c) 체인 위조
        callSignupFrom("1.2.3.4", "X-Forwarded-For", "");                       // (d) 빈 문자열
        callSignupFrom("1.2.3.4",                                               // (e) 그 외 프록시 헤더 총동원
                "X-Real-IP", "7.7.7.7",
                "Forwarded", "for=6.6.6.6;proto=https",
                "X-Client-IP", "5.5.5.5",
                "X-Forwarded-For", "9.9.9.9");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixedWindow, times(5)).tryAcquire(key.capture(), eq(5), eq(60_000L));

        assertThat(key.getAllValues()).allSatisfy(k -> assertThat(k).contains("1.2.3.4"));
        assertThat(key.getAllValues()).containsOnly(key.getAllValues().get(0));  // 5회 전부 동일 키
        assertThat(key.getAllValues()).allSatisfy(k ->
                assertThat(k).doesNotContain("9.9.9.9", "8.8.8.8", "7.7.7.7", "6.6.6.6", "5.5.5.5"));
    }

    @Test
    @DisplayName("폴링 경로는 Token Bucket에 cap 5 / refill 1.0/s를 그대로 넘긴다 (상수→Redis 배선)")
    void pollPathPassesPollBucketParameters() throws Exception {
        // 상수만 바꿔 놓고 호출부에 반영이 안 되면 Lua 쪽 테스트는 (파라미터를 직접 주므로) 전부 초록이다.
        // 필터가 실제로 그 값을 넘기는지는 여기서만 드러난다.
        // refill을 0.5로 되돌리면 이 테스트가 깨진다.
        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/v1/queues/q_test_wiring/tokens/tok_1");
        req.setRemoteAddr("1.2.3.4");

        filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());

        verify(tokenBucket).tryAcquire(contains("tok_1"), eq(5), eq(1.0));
        verifyNoInteractions(fixedWindow);   // 폴링은 인증 전 Fixed Window 분기로 새지 않는다
    }

    /**
     * 🔴 <b>인코딩한 경로가 한도를 우회하면 안 된다.</b>
     *
     * <p>{@code getRequestURI()}는 디코딩 전 원문이라 {@code equals("/api/v1/tenants/login")}이
     * 빗나가고, 매칭 실패 시 필터가 <b>무제한으로 통과시킨다.</b> 반면 시큐리티·디스패처는
     * 디코딩된 경로로 판정해 로그인을 그대로 수행한다 — <b>두 계층이 다른 문자열을 본다.</b>
     *
     * <p>2026-08-28 실측(수정 전): {@code POST /api/v1/tenants/log%69n} 15회가 전부 401이고
     * 429가 0건이었다. 평문은 11회째 429다. 본문이 {@code T003}이라
     * <b>자격 증명 비교가 실제로 수행됐다</b> = brute force에 한도가 없다.
     *
     * <p>이 케이스가 없으면 {@code getRequestURI()} 한 줄로 되돌려도 전체 테스트가 초록이다.
     *
     * <p>⚠️ <b>목의 한계.</b> {@code MockHttpServletRequest}는 준 문자열을 그대로
     * {@code getRequestURI()}로 돌려준다. {@code %69}·{@code %74} 케이스는 실제 Tomcat과 결과가
     * 같은 것을 라이브로 확인했지만, {@code ;x=1}·{@code //}·{@code %2F}는 <b>라이브에서는 필터에
     * 도달조차 못 한다</b>(Tomcat·StrictHttpFirewall이 앞에서 400). 그런 입력으로 케이스를 추가하면
     * "필터가 막았다"를 단정하게 되는데 <b>실제 방어자는 firewall이다.</b> 여기에 넣지 마라.
     */
    @Test
    @DisplayName("퍼센트 인코딩한 /log%69n 도 평문과 같은 LOGIN IP 윈도우 키를 쓴다")
    void percentEncodedPublicPathUsesSameRateLimitKey() throws Exception {
        MockHttpServletRequest plain =
                new MockHttpServletRequest("POST", "/api/v1/tenants/login");
        plain.setRemoteAddr("1.2.3.4");
        filter.doFilterInternal(plain, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest encoded =
                new MockHttpServletRequest("POST", "/api/v1/tenants/log%69n");
        encoded.setRemoteAddr("1.2.3.4");
        filter.doFilterInternal(encoded, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        // 🔑 호출 자체가 2회여야 한다. 우회하면 인코딩 요청은 Limiter를 아예 안 부르고 통과한다.
        verify(fixedWindow, times(2)).tryAcquire(key.capture(), anyInt(), anyLong());
        assertThat(key.getAllValues().get(1)).isEqualTo(key.getAllValues().get(0));
        assertThat(key.getAllValues()).allSatisfy(k -> assertThat(k).contains("login"));
    }

    /**
     * 🔴 <b>인코딩 변형마다 폴링 버킷이 새로 생기면 안 된다.</b>
     *
     * <p>버킷 키를 원문 마지막 세그먼트에서 뽑으면 {@code tok_...}의 한 글자만 {@code %XX}로
     * 바꿔도 다른 키가 된다. tokenId가 40자라 변형은 사실상 무제한이고,
     * <b>폴링 한도(cap 5 / refill 1.0/s)가 통째로 사라진다.</b>
     *
     * <p>2026-08-28 실측(수정 전): 평문으로 버킷을 소진해 429가 난 뒤,
     * {@code t}를 {@code %74}로 바꾼 같은 토큰이 <b>200을 5번 더 받았다.</b>
     *
     * <p>폴링 1건 = Redis master EVAL 1회(실측 42μs)이고 한 큐는 마스터 1대에 고정이라
     * (§75 D26) 분산되지 않는다. 이 한도가 그 단일 스레드를 지키는 유일한 장치다.
     */
    @Test
    @DisplayName("tokenId를 인코딩해도 같은 폴링 버킷 키를 쓴다 (변형마다 새 버킷이 생기지 않는다)")
    void percentEncodedTokenIdSharesOnePollBucket() throws Exception {
        MockHttpServletRequest plain =
                new MockHttpServletRequest("GET", "/api/v1/queues/q_1/tokens/tok_abc");
        plain.setRemoteAddr("1.2.3.4");
        filter.doFilterInternal(plain, new MockHttpServletResponse(), new MockFilterChain());

        // tok_abc 의 't' 를 %74 로 — 같은 토큰을 가리키지만 원문 문자열은 다르다
        MockHttpServletRequest encoded =
                new MockHttpServletRequest("GET", "/api/v1/queues/q_1/tokens/%74ok_abc");
        encoded.setRemoteAddr("1.2.3.4");
        filter.doFilterInternal(encoded, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(tokenBucket, times(2)).tryAcquire(key.capture(), eq(5), eq(1.0));
        assertThat(key.getAllValues().get(1)).isEqualTo(key.getAllValues().get(0));
        assertThat(key.getAllValues()).allSatisfy(k -> assertThat(k).contains("tok_abc"));
    }

    @Test
    @DisplayName("remoteAddr가 다르면 키도 달라진다 (IP 단위 격리가 살아있는지)")
    void differentPeerYieldsDifferentKey() throws Exception {
        callSignupFrom("1.2.3.4", "X-Forwarded-For", "9.9.9.9");
        callSignupFrom("1.2.3.5", "X-Forwarded-For", "9.9.9.9");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixedWindow, times(2)).tryAcquire(key.capture(), anyInt(), anyLong());

        assertThat(key.getAllValues().get(0)).isNotEqualTo(key.getAllValues().get(1));
    }
}
