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

class InMemoryFixedWindowRateLimiterTest {

    @Test
    @DisplayName("한 창에서 limit까지는 허용하고 그 이후는 거절한다")
    void allowsUpToLimitThenRejects() {
        // 설정(limit/windowMillis)은 생성자에서 받는다.
        // tryAcquire의 capacity/refillRate 인자는 FixedWindow가 무시한다(placeholder 0, 0.0).
        RateLimiter limiter = new InMemoryFixedWindowRateLimiter(100, 60_000);

        // limit(100)번은 모두 허용
        for (int i = 1; i <= 100; i++) {
            assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0))
                    .as("%d번째 요청", i)
                    .isTrue();
        }
        // 101번째부터는 거절
        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isFalse();
        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isFalse();
    }

    @Test
    @DisplayName("키가 다르면 카운터가 독립적이다")
    void differentKeysAreIndependent() {
        RateLimiter limiter = new InMemoryFixedWindowRateLimiter(1, 60_000);

        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isTrue();   // tenant 1 소진
        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isFalse();
        assertThat(limiter.tryAcquire("rl:tenant:2", 0, 0.0)).isTrue();   // tenant 2는 영향 없음
    }

    @Test
    @DisplayName("창이 바뀌면 카운터가 리셋된다")
    void resetsAfterWindow() throws InterruptedException {
        RateLimiter limiter = new InMemoryFixedWindowRateLimiter(1, 200); // 200ms 창

        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isTrue();   // 0번 창 소진
        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isFalse();

        Thread.sleep(250); // 다음 창으로 넘어감

        assertThat(limiter.tryAcquire("rl:tenant:1", 0, 0.0)).isTrue();   // 리셋되어 다시 허용
    }

    @Test
    @DisplayName("동시 요청에서도 정확히 limit개만 허용된다 (compute 원자성 검증)")
    void concurrentRequestsAllowExactlyLimit() throws InterruptedException {
        int limit = 100;
        int totalRequests = 1_000;
        // 모든 요청이 한 창 안에 들어오도록 창을 충분히 크게
        RateLimiter limiter = new InMemoryFixedWindowRateLimiter(limit, 60_000);

        // 요청 1건당 가상 스레드 1개 → totalRequests개가 동시에 ready 후 start 대기.
        // (고정 50스레드 풀이면 50개만 start.await()에 묶이고 ready가 0에 못 닿아 교착됨)
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        IntStream.range(0, totalRequests).forEach(i -> pool.submit(() -> {
            ready.countDown();
            try {
                start.await();                       // 모든 스레드를 동시에 출발시킴
                if (limiter.tryAcquire("rl:tenant:1", 0, 0.0)) {
                    allowed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        ready.await();
        start.countDown();                           // 일제 시작 (race 유도)
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // ★ 핵심: compute()로 원자화했다면 정확히 limit개.
        //   만약 get→put 으로 나눴다면 Lost Update로 limit보다 많이 통과한다.
        assertThat(allowed.get()).isEqualTo(limit);
    }
}
