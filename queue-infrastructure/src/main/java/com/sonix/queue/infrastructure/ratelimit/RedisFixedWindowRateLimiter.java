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

        // 윈도우 번호까지 붙인 '실제로 만질 키'를 KEYS[1]로 넘긴다. 스크립트가 키를 조립하면
        // 선언한 키와 접근하는 키가 달라져 Cluster가 거부한다(RateLimitKeys.fixedWindow 참조).
        Long result = redisTemplate.execute(
                fixedWindowScript,
                List.of(RateLimitKeys.fixedWindow(key, now, windowSizeMillis)),
                String.valueOf(limit),
                String.valueOf(windowSizeMillis)
        );

        return result != null && result == 1L;
    }
}
