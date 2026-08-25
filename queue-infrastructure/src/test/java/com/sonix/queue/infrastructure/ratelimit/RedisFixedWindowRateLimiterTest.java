package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Fixed Window Rate Limiter 회귀 테스트 (실 Redis Cluster).
 *
 * <p><b>이 클래스가 존재하는 이유:</b> {@code fixed-window.lua}가
 * {@code KEYS[1] .. ':' .. windowNo}로 <b>키를 직접 조립해</b> INCR하고 있었다. 선언한 KEYS와
 * 실제 접근 키가 달라 Redis Cluster는 이를 거부한다
 * ({@code ERR Script attempted to access a non local key}). Sentinel에는 슬롯 개념이 없어
 * Sprint 5-C부터 잠복해 있다가 Cluster 전환 순간 signup/login/refresh가 전부 500이 됐다.
 *
 * <p>단위 테스트로는 절대 못 잡는다 — <b>실 Cluster에 붙어야만</b> 드러난다. 그래서 이 테스트는
 * {@code @SpringBootTest}로 진짜 연결을 쓰고, 슬롯이 서로 다른 키 여러 개로 확인한다.
 * (키 하나만 쓰면 "우연히 base와 같은 슬롯"인 1/16384를 배제했다고 말하기 어렵다)
 */
@SpringBootTest(classes = RateLimitRedisTestConfig.class)
@Tag("redis")
class RedisFixedWindowRateLimiterTest {

    @Autowired
    private FixedWindowRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 실제로 만들어지는 키는 base + ":" + 윈도우번호라, 정리 대상도 그것들이다. */
    private final List<String> createdBaseKeys = new ArrayList<>();

    private static final long WINDOW_MILLIS = 60_000L;

    private String newBaseKey() {
        String key = "q_dev_rl:fixedwindow:" + UUID.randomUUID();
        createdBaseKeys.add(key);
        return key;
    }

    @AfterEach
    void cleanUp() {
        long now = System.currentTimeMillis();
        for (String base : createdBaseKeys) {
            // 테스트 중 윈도우 경계를 넘었을 수 있으므로 앞뒤 윈도우까지 지운다.
            for (long offset = -1; offset <= 1; offset++) {
                redisTemplate.delete(RateLimitKeys.fixedWindow(base, now + offset * WINDOW_MILLIS, WINDOW_MILLIS));
            }
        }
        createdBaseKeys.clear();
    }

    @Test
    @DisplayName("Cluster에서 예외 없이 동작하고, limit까지 허용 후 거부한다")
    void allowsUpToLimitThenDenies() {
        String base = newBaseKey();
        int limit = 5;

        for (int i = 1; i <= limit; i++) {
            assertThat(rateLimiter.tryAcquire(base, limit, WINDOW_MILLIS))
                    .as("%d번째 요청은 허용돼야 한다", i)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquire(base, limit, WINDOW_MILLIS))
                .as("limit 초과분은 거부")
                .isFalse();
    }

    @Test
    @DisplayName("슬롯이 서로 다른 키 12개 전부에서 non local key 없이 실행된다 (4 master 전수)")
    void worksAcrossSlots() {
        for (int i = 0; i < 12; i++) {
            String base = newBaseKey();
            assertThatCode(() -> assertThat(rateLimiter.tryAcquire(base, 3, WINDOW_MILLIS)).isTrue())
                    .as("키가 스크립트 안에서 조립되면 여기서 non local key로 죽는다")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("카운터는 base가 아니라 '윈도우 번호가 붙은 키'에 쌓인다 (키 문자열 규약 고정)")
    void countsOnWindowScopedKey() {
        String base = newBaseKey();
        long now = System.currentTimeMillis();

        rateLimiter.tryAcquire(base, 5, WINDOW_MILLIS);

        String windowKey = RateLimitKeys.fixedWindow(base, now, WINDOW_MILLIS);
        assertThat(redisTemplate.opsForValue().get(windowKey))
                .as("Lua가 키를 조립하던 시절과 같은 키 문자열이어야 기존 카운터·TTL 의미가 보존된다")
                .isEqualTo("1");
        assertThat(redisTemplate.hasKey(base)).isFalse();
        assertThat(redisTemplate.getExpire(windowKey)).isPositive();
    }
}
