package com.sonix.queue.infrastructure.config;

import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.infrastructure.ratelimit.RedisTokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 연결 설정 (Sentinel 기반, 최소 구성).
 *
 * <p>RedisAutoConfiguration은 application.yml에서 여전히 제외 상태이므로,
 * 여기서 명시적으로 {@link LettuceConnectionFactory}를 정의한다 (학습/제어 목적).
 *
 * <p>Sentinel을 통해 현재 Master를 자동 발견하므로, Failover로 Master 포트가
 * 바뀌어도 애플리케이션 코드는 영향받지 않는다 (REDIS_SENTINEL.md 참고).
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.sentinel.master:mymaster}") String master,
            @Value("${spring.data.redis.sentinel.nodes:127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381}") String nodes) {

        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration().master(master);
        for (String node : nodes.split(",")) {
            String[] hostPort = node.trim().split(":");
            sentinel.sentinel(hostPort[0], Integer.parseInt(hostPort[1]));
        }
        return new LettuceConnectionFactory(sentinel);
    }

    /**
     * 문자열 기반 작업용 템플릿. Rate Limiter의 INCR/EXPIRE, Lua EVAL 등에 사용.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // Helper 메서드 (Bean 아님)
    private <T> RedisScript<T> loadScript(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    @Bean
    public RedisScript<Long> tokenBucketScript() {
        return loadScript("lua/token-bucket.lua", Long.class);
    }


    @Bean
    public RateLimiter rateLimiter(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> tokenBucketScript
    ) {
        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                tokenBucketScript
        );
    }
}
