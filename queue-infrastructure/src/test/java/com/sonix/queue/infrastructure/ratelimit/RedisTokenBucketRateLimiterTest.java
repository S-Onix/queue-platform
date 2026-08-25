package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.domain.tenant.Plan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("redis")
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

    /**
     * 남긴 키를 지운다. Redis는 다른 에이전트와 공유하는 자원이라 테스트 잔재를 남기지 않는다.
     *
     * <p>TTL 상한 클램프(3600)가 없던 중간 시점에는 회복 속도를 일부러 낮게 잡은 테스트
     * (refill 0.001)가 27.8시간짜리 키를 남겼다 — 잔재 키 TTL이 99,000초대인 것을 실제로 확인했다.
     * 지금은 클램프로 최대 1시간이지만, 공유 Redis에 1시간짜리를 매 실행 쌓을 이유도 없다.
     */
    @AfterEach
    void cleanUp() {
        List<String> keys = new ArrayList<>(List.of(
                testKey, testKey + ":1", testKey + ":2",
                testKey + ":poll", testKey + ":zero", testKey + ":tiny"));
        for (Plan plan : Plan.values()) {
            keys.add(testKey + ":plan:" + plan);
        }
        redisTemplate.delete(keys);
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
    @DisplayName("키 TTL은 full refill 시간 + 60초 — 폴링(cap5,refill1)=65s, Tenant Plan 전 등급=120s")
    void ttlFollowsFullRefillTime() {
        // 3600 고정이던 시절엔 폴링 키가 토큰 하나당 1시간씩 남았다. 폴링 키는 대기 토큰 수만큼
        // 생기므로 이 상수가 곧 Redis 메모리 상한이다. 파라미터가 달라도 같은 스크립트를
        // 폴링/Tenant Plan이 공유하므로, 두 호출자 값을 모두 여기서 못박는다.
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate, tokenBucketScript);

        String pollKey = testKey + ":poll";
        limiter.tryAcquire(pollKey, 5, 1.0);                 // RateLimitFilter.POLL_CAPACITY / POLL_REFILL_PER_SEC
        assertThat(redisTemplate.getExpire(pollKey))
                .as("폴링 버킷 TTL = ceil(5/1.0) + 60")
                .isBetween(64L, 65L);                        // 1초 여유: TTL 조회까지의 경과분

        for (Plan plan : Plan.values()) {
            String planKey = testKey + ":plan:" + plan;
            limiter.tryAcquire(planKey, plan.getCapacity(), plan.getRefillRatePerSecond());
            assertThat(redisTemplate.getExpire(planKey))
                    .as("%s TTL = ceil(%d/%s) + 60", plan, plan.getCapacity(), plan.getRefillRatePerSecond())
                    .isBetween(119L, 120L);                  // Plan은 capacity = refill×60 비율 고정
        }
    }

    @Test
    @DisplayName("TTL 계산이 폭주하는 입력에도 키는 60~3600초 안에 남는다 (TTL 없는 키가 영구히 남지 않는다)")
    void ttlIsClampedForPathologicalRefillRates() {
        // capacity/refillRate는 호출자가 넘기는 값이라 스크립트가 통제하지 못한다.
        // 클램프 이전에 실측한 실패 모드:
        //   refill 0    → ceil(inf) → EXPIRE 인자 오류로 스크립트 중단.
        //                 HMSET은 이미 실행된 뒤라 TTL=-1(영구) 키가 남았다. 실제 확인함.
        //   refill 0.001 → TTL 100,060초(27.8시간). 줄이려던 메모리가 오히려 늘었다.
        // 현재 호출자(폴링 1.0, Plan 1.67~1666.67)는 둘 다 안 밟지만, 포트 시그니처가
        // double을 그대로 받으므로 새 호출자가 언제든 밟을 수 있다.
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate, tokenBucketScript);

        String zeroKey = testKey + ":zero";
        assertThat(limiter.tryAcquire(zeroKey, 5, 0.0)).as("0으로 나눠도 예외 없이 판정된다").isTrue();
        assertThat(redisTemplate.getExpire(zeroKey)).as("TTL 없는 영구 키 금지").isBetween(60L, 3600L);

        String tinyKey = testKey + ":tiny";
        limiter.tryAcquire(tinyKey, 100, 0.001);
        assertThat(redisTemplate.getExpire(tinyKey)).as("상한 1시간").isBetween(60L, 3600L);
    }

    @Test
    @DisplayName("폴링 파라미터(cap5, refill1.0)는 2초 대기당 토큰 2개를 돌려준다 — 2초 간격 폴링에 여유가 남는다")
    void pollBucketRefillsFasterThanPollInterval() {
        // refill 0.5/s였을 때는 2초에 1개만 회복돼 nextPollAfterSec 최소값(2초)과 소비 속도가
        // 정확히 같았다 — 여유 0. 이 테스트는 0.5/s로 되돌리면 두 번째 acquire에서 깨진다.
        RateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate, tokenBucketScript);

        for (int i = 1; i <= 5; i++) {                        // capacity 소진
            assertThat(limiter.tryAcquire(testKey, 5, 1.0)).as("%d번째 burst", i).isTrue();
        }
        assertThat(limiter.tryAcquire(testKey, 5, 1.0)).isFalse();

        await(2_000);

        assertThat(limiter.tryAcquire(testKey, 5, 1.0)).as("2초 회복분 1개째").isTrue();
        assertThat(limiter.tryAcquire(testKey, 5, 1.0)).as("2초 회복분 2개째").isTrue();
        // 3개째가 거부되는지는 안 본다. sleep이 1초 넘게 오버슈트하면(공유 머신) 통과해버려 플레이키다.
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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