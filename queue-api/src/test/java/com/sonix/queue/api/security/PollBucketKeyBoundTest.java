package com.sonix.queue.api.security;

import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.domain.tenant.TenantCache;
import com.sonix.queue.domain.tenant.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 폴링 버킷 키의 <b>크기</b>가 유계인지.
 *
 * <p>계기는 2026-09-23 실측이다. 인증도 바디도 없는 GET 하나가 경로에 7,000자를 실어
 * <b>Redis 키 7,272 B</b>를 만들었다(§87 예산이 477 B/명인데 요청 1건이 그 15배다).
 * {@code noeviction} 이라 종착점은 <b>같은 마스터의 다른 테넌트 enqueue 503</b> 이다.
 *
 * <p>🔑 여기서 보는 것은 "429가 났는가"가 아니라 <b>어떤 키가 Redis 로 나갔는가</b>다.
 * 응답만 보면 길든 짧든 404라 결함 상태에서도 초록이다.
 */
class PollBucketKeyBoundTest {

    private final RateLimiter tokenBucket = mock(RateLimiter.class);

    private String keyFor(String tokenId) throws Exception {
        when(tokenBucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(true);
        FixedWindowRateLimiter fixedWindow = mock(FixedWindowRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(
                tokenBucket, fixedWindow, mock(TenantRepository.class), mock(TenantCache.class), 0, 0);

        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/v1/queues/q_1/tokens/" + tokenId);
        req.setRemoteAddr("127.0.0.1");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(tokenBucket).tryAcquire(key.capture(), anyInt(), anyDouble());
        return key.getValue();
    }

    @Test
    @DisplayName("정상 tokenId 는 그대로 자기 버킷을 쓴다 — 개인 한도가 유지된다")
    void normalTokenIdKeepsOwnBucket() throws Exception {
        String tokenId = "tok_0192f0c1-2d3e-7abc-9def-0123456789ab";   // 4 + 36 = 40자
        assertThat(keyFor(tokenId)).isEqualTo("rl:poll:token:" + tokenId);
    }

    @Test
    @DisplayName("🔴 상한을 넘는 tokenId 는 한 버킷으로 합쳐진다 — 요청당 새 키가 생기지 않는다")
    void oversizeTokenIdCollapsesToOneBucket() throws Exception {
        String key = keyFor("tok_" + "A".repeat(7000));

        assertThat(key).isEqualTo("rl:poll:token:__oversize__");
        assertThat(key.length()).isLessThan(64);   // 7,000자가 키로 나가지 않는다
    }
}
