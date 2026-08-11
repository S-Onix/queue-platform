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
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        fixedWindow = mock(FixedWindowRateLimiter.class);
        when(fixedWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        filter = new RateLimitFilter(
                mock(RateLimiter.class), fixedWindow,
                mock(TenantRepository.class), mock(TenantCache.class));
    }

    /** 인증 전 분기를 타야 하므로 SecurityContext는 비운 상태를 보장한다. */
    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
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
    @DisplayName("remoteAddr가 다르면 키도 달라진다 (IP 단위 격리가 살아있는지)")
    void differentPeerYieldsDifferentKey() throws Exception {
        callSignupFrom("1.2.3.4", "X-Forwarded-For", "9.9.9.9");
        callSignupFrom("1.2.3.5", "X-Forwarded-For", "9.9.9.9");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixedWindow, times(2)).tryAcquire(key.capture(), anyInt(), anyLong());

        assertThat(key.getAllValues().get(0)).isNotEqualTo(key.getAllValues().get(1));
    }
}
