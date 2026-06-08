package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketRateLimiterTest {

    @Test
    @DisplayName("양동이가 가득 차있으면 capacity까지 burst 허용 후 거부한다")
    void allowsBurstUpToCapacityThenRejects() {
        // capacity 100, 초당 1 토큰 회복 (1분 = 60 토큰)
        RateLimiter limiter = new InMemoryTokenBucketRateLimiter();

        // 즉시 100건 burst → 모두 허용 (양동이 가득)
        for (int i = 1; i <= 100; i++) {
            assertThat(limiter.tryAcquire("rl:tenant:1", 100, 1.0))
                    .as("%d번째 요청", i)
                    .isTrue();
        }

        // 101번째는 거부 (양동이 비었고, 회복 시간 부족)
        assertThat(limiter.tryAcquire("rl:tenant:1", 100, 1.0)).isFalse();
        assertThat(limiter.tryAcquire("rl:tenant:1", 100, 1.0)).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 양동이가 독립적이다")
    void differentKeysAreIndependent() {
        // capacity 1, 회복 매우 느림 (실질적으로 첫 토큰만 사용 가능)
        RateLimiter limiter = new InMemoryTokenBucketRateLimiter();

        assertThat(limiter.tryAcquire("rl:tenant:1", 1, 0.001)).isTrue();    // tenant 1 소진
        assertThat(limiter.tryAcquire("rl:tenant:1", 1, 0.001)).isFalse();
        assertThat(limiter.tryAcquire("rl:tenant:2", 1, 0.001)).isTrue();    // tenant 2는 영향 없음
    }

    @Test
    @DisplayName("시간이 지나면 토큰이 회복되어 다시 허용된다")
    void refillsTokensOverTime() throws InterruptedException {
        // capacity 1, 초당 10 토큰 회복 (= 100ms마다 1 토큰)
        RateLimiter limiter = new InMemoryTokenBucketRateLimiter();

        assertThat(limiter.tryAcquire("rl:tenant:1", 1, 10.0)).isTrue();    // 양동이 소진
        assertThat(limiter.tryAcquire("rl:tenant:1", 1, 10.0)).isFalse();   // 즉시 거부

        Thread.sleep(150);  // 100ms 이상 대기 → 1 토큰 이상 회복

        assertThat(limiter.tryAcquire("rl:tenant:1", 1, 10.0)).isTrue();    // 회복되어 다시 허용
    }

    @Test
    @DisplayName("동시 요청에서도 정확히 capacity개만 허용된다 (compute 원자성 검증)")
    void concurrentRequestsAllowExactlyCapacity() throws InterruptedException {
        int capacity = 100;
        int totalRequests = 1_000;
        // 회복 거의 없도록 매우 낮은 refill rate (테스트 중엔 추가 토큰 사실상 0)
        RateLimiter limiter = new InMemoryTokenBucketRateLimiter();

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
                if (limiter.tryAcquire("rl:tenant:1", capacity, 0.001)) {
                    allowed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        ready.await();
        start.countDown();  // 일제 시작 (race 유도)
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // ★ 핵심: compute()로 원자화했다면 정확히 capacity개.
        //   만약 get→put 으로 나눴다면 Lost Update로 capacity보다 많이 통과한다.
        //   (테스트 실행 시간 동안 회복되는 토큰은 0.001 × 5초 = 0.005 토큰으로 무시 가능)
        assertThat(allowed.get()).isEqualTo(capacity);
    }
}