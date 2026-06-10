package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

public class RedisFixedWindowRateLimiter implements FixedWindowRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> fixedWindowScript;

    public RedisFixedWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> fixedWindowScript
    ) {
        this.redisTemplate = redisTemplate;
        this.fixedWindowScript = fixedWindowScript;
    }

    @Override
    public boolean tryAcquire(String key, int limit, long windowSizeMillis) {
        long now = System.currentTimeMillis();

        Long result = redisTemplate.execute(
                fixedWindowScript,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(windowSizeMillis),
                String.valueOf(now)
        );

        return result != null && result == 1L;
    }
}
