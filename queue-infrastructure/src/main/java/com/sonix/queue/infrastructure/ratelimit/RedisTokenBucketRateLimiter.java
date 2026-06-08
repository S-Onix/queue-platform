package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

public class RedisTokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> tokenBucketScript;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> tokenBucketScript
    ) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    @Override
    public boolean tryAcquire(String key, int capacity, double refillRatePerSecond) {
        long now = System.currentTimeMillis();

        Long result = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),                              // KEYS
                String.valueOf(capacity),                  // ARGV[1]
                String.valueOf(refillRatePerSecond),       // ARGV[2]
                String.valueOf(now)                        // ARGV[3]
        );

        // Lua가 반환한 1 = 허용, 0 = 거부
        return result != null && result == 1L;
    }
}
