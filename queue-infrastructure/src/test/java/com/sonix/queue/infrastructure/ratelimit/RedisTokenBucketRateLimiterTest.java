package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisTokenBucketRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisScript<Long> tokenBucketScript;
    private String testKey;

    @BeforeEach
    void setUp() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/token-bucket.lua"));
        script.setResultType(Long.class);
        tokenBucketScript = script;

        // 테스트마다 새 키 사용 (다른 테스트와 격리)
        testKey = "test:rl:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("양동이가 가득 차있으면 capacity까지 burst 허용 후 거부한다")
    void allowsBurstUpToCapacityThenRejects() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate, tokenBucketScript
        );

        // 10건 burst → 모두 허용
        for (int i = 1; i <= 10; i++) {
            assertThat(limiter.tryAcquire(testKey, 10, 1.0))
                    .as("%d번째 요청", i)
                    .isTrue();
        }

        // 11번째는 거부
        assertThat(limiter.tryAcquire(testKey, 10, 1.0)).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 양동이가 독립적이다")
    void differentKeysAreIndependent() {
        RateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate, tokenBucketScript
        );

        String key1 = testKey + ":1";
        String key2 = testKey + ":2";

        assertThat(limiter.tryAcquire(key1, 1, 0.001)).isTrue();
        assertThat(limiter.tryAcquire(key1, 1, 0.001)).isFalse();
        assertThat(limiter.tryAcquire(key2, 1, 0.001)).isTrue();
    }

    @Test
    @DisplayName("시간이 지나면 토큰이 회복되어 다시 허용된다")
    void refillsTokensOverTime() throws InterruptedException {
        // capacity 1, 초당 10 토큰 회복 (= 100ms마다 1 토큰)
        RateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate, tokenBucketScript
        );

        assertThat(limiter.tryAcquire(testKey, 1, 10.0)).isTrue();    // 양동이 소진
        assertThat(limiter.tryAcquire(testKey, 1, 10.0)).isFalse();   // 즉시 거부

        Thread.sleep(150);  // 100ms 이상 대기 → 1 토큰 이상 회복

        assertThat(limiter.tryAcquire(testKey, 1, 10.0)).isTrue();    // 회복 후 허용
    }

    @Test
    @DisplayName("동시 요청에서도 정확히 capacity개만 허용된다 (Lua 원자성)")
    void concurrentRequestsAllowExactlyCapacity() throws InterruptedException {
        int capacity = 100;
        int totalRequests = 1_000;
        RateLimiter limiter = new RedisTokenBucketRateLimiter(
                redisTemplate, tokenBucketScript
        );

        // 요청 1건당 가상 스레드 1개 → totalRequests개가 동시에 ready 후 start 대기.
        // (고정 50스레드 풀이면 50개만 start.await()에 묶이고 ready가 0에 못 닿아 교착됨)
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        IntStream.range(0, totalRequests).forEach(i -> pool.submit(() -> {
            ready.countDown();
            try {
                start.await();  // 모든 스레드 동시 출발
                if (limiter.tryAcquire(testKey, capacity, 0.001)) {  // 회복 거의 없음
                    allowed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // ★ 핵심: Lua가 원자 실행 보장 → 정확히 capacity개
        assertThat(allowed.get()).isEqualTo(capacity);
    }
}