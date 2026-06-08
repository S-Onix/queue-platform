package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.infrastructure.config.RedisConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Rate Limit Redis 통합 테스트 전용 부트스트랩 설정.
 *
 * <p>queue-infrastructure 모듈에는 {@code @SpringBootApplication}(앱 클래스)이 없다.
 * 앱 클래스는 queue-api에 있고 infra는 api를 의존하지 않으므로(헥사고날 단방향),
 * {@code @SpringBootTest}가 찾을 {@code @SpringBootConfiguration}을 테스트 소스에 직접 둔다.
 *
 * <p><b>@EnableAutoConfiguration을 일부러 붙이지 않는다.</b> 붙이면 JPA(MySQL)/Kafka
 * 오토컨피그까지 끌려와 불필요하게 무겁고 외부 인프라에 묶인다. 대신 Redis Rate Limiter에
 * 필요한 빈({@link RedisConfig}: ConnectionFactory, StringRedisTemplate, tokenBucketScript,
 * RateLimiter)만 {@code @Import}로 명시 로딩한다.
 *
 * <p>같은 패키지의 {@code @SpringBootTest}가 이 설정을 자동 탐색한다.
 */
@SpringBootConfiguration
@Import(RedisConfig.class)
public class RateLimitRedisTestConfig {
}
