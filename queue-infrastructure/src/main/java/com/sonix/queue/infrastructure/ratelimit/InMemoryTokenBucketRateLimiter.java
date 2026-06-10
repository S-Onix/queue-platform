package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();


    private record Bucket (double tokens, long lastRefillMillis) {}

    @Override
    public boolean tryAcquire(String key, int capacity, double refillRatePerSecond) {
        //현재 양동이에서 넣을 수 있는지 확인해야함.
        //양동이는 시간이 지나면 회복되야함
        long now = System.currentTimeMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);

        buckets.compute(key, (k, prev) -> {
            // 이전 상태 확인
            double prevTokens = (prev == null) ? capacity : prev.tokens();
            long prevTime = (prev == null) ? now : prev.lastRefillMillis();

            // 경과 시간만큼 토큰 회복
            long elapsedMillis = now - prevTime;
            double refilled = elapsedMillis * refillRatePerSecond / 1000.0;
            double currentTokens = Math.min(capacity, prevTokens + refilled);

            // 토큰 차감 시도
            if (currentTokens >= 1.0) {
                acquired.set(true);
                return new Bucket(currentTokens - 1.0, now);
            } else {
                acquired.set(false);
                return new Bucket(currentTokens, now);
            }
        });


        return acquired.get();
    }
}
