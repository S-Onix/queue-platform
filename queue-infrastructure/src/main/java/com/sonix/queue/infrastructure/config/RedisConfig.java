package com.sonix.queue.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.infrastructure.cache.mixin.CacheMixinRegistrar;
import com.sonix.queue.infrastructure.ratelimit.RedisFixedWindowRateLimiter;
import com.sonix.queue.infrastructure.ratelimit.RedisTokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

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

    /**
     * Redis 커맨드 응답 대기 상한.
     *
     * <p>Lettuce 기본값은 60초다. 그 값이면 종료 경로에 시한이 없어진다 —
     * Redis가 응답하지 못하는 상태에서 SIGTERM이 오면 BatchProcessor의 마지막 drain이
     * 실행 중인 커맨드 하나에 60초를 매달리고, {@code SmartLifecycle.stop()}은 동기라
     * {@code timeout-per-shutdown-phase}로도 끊을 수 없다.
     *
     * <p><b>트레이드오프:</b> Sentinel failover 실측 5~10초({@code doc/INFRA_SETUP.md})를
     * 못 타고 넘는 대신, 종료 시한을 확정한다. 실패가 <b>5xx로 드러나는 것</b>(흔적이 남음)과
     * SIGKILL로 인한 in-memory 유실(<b>회복 불가·검출 불가</b>)을 견주어, 드러나는 실패를 택했다.
     *
     * <p><b>⚠️ 단, enqueue 경로에서 이 실패는 "재시도하면 회복된다"가 아니다.</b>
     * 클라이언트 타임아웃은 서버 실행을 취소하지 않는다. Lua가 Redis에서 이미 성공한 뒤
     * 이 시한에 걸려 포기하면, 사용자가 재시도해도 {@code enqueue_bulk.lua}의 {@code ZADD NX}가
     * 0을 반환해 <b>EXISTS</b>로 떨어진다. {@code QueueEngineService.enqueue()}는
     * {@code if (result.isOk())}일 때만 Kafka에 발행하므로 <b>발행이 스킵되고 DB row가 생기지 않는다</b>
     * — Redis에만 있고 DB에 없는 좀비 WAITING이 된다.
     *
     * <p>블라스트 반경은 1건이 아니다. Lua는 요청 스레드가 아니라 {@code BatchProcessor.processChunk}에서
     * 실행되므로, 이 타임아웃 1회가 <b>청크 하나(최대 {@code CHUNK_SIZE}=500건)</b>를 통째로 실패시킨다.
     *
     * <p>이 창은 60초에서도 동일하게 존재했고 5초는 <b>빈도만 바꾼다</b>(창을 넓힌다).
     * 해소는 이 값을 되돌리는 것이 아니라 <b>reconciliation 스위퍼</b>(Redis↔DB 대조 후 보정)의
     * 몫이다 — 최우선 후속 과제로 등록돼 있다.
     *
     * <p>같은 프로젝트의 Kafka 프로듀서도 request 3s / delivery 8s / send 12s로 전부
     * 시한이 명시돼 있다. Redis만 무기한인 것은 설계가 아니라 누락이었다.
     *
     * <p><b>⚠️ 이 값이 확정하는 것은 "Redis 쪽" 시한뿐이다.</b> 종료 drain의 상한
     * ({@code BatchProcessor.SHUTDOWN_DRAIN_TIMEOUT_MS 5s} + 이 값 5s ≈ 10s)은
     * <b>MySQL이 응답한다는 전제 위에서만</b> 성립한다. 같은 사이클의 DB 호출
     * ({@code findByQueueId}, 캐시 없음)은 JDBC {@code socketTimeout} 미설정
     * (Connector/J 기본 0 = 무기한)이라 시한이 없고, DB 무응답 시 종료 상한도 없다.
     * → 후속 과제(부하 검증 후 별도 브랜치).
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.sentinel.master:mymaster}") String master,
            @Value("${spring.data.redis.sentinel.nodes:127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381}") String nodes) {

        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration().master(master);
        for (String node : nodes.split(",")) {
            String[] hostPort = node.trim().split(":");
            sentinel.sentinel(hostPort[0], Integer.parseInt(hostPort[1]));
        }
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .build();

        return new LettuceConnectionFactory(sentinel, clientConfig);
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
    public RedisScript<Long> fixedWindowScript() {
        return loadScript("lua/fixed-window.lua", Long.class);
    }

    @Bean
    public RedisScript<List> enqueueBulkScript() {
        return loadScript("lua/enqueue_bulk.lua", List.class);
    }

    @Bean
    public RedisScript<Long> pollVerifyScript() {
        return loadScript("lua/poll_verify.lua", Long.class);
    }

    @Bean
    public RateLimiter rateLimiter(
            StringRedisTemplate redisTemplate,
            @Qualifier("tokenBucketScript") RedisScript<Long> tokenBucketScript
    ) {
        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                tokenBucketScript
        );
    }

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter(
            StringRedisTemplate redisTemplate,
            @Qualifier("fixedWindowScript") RedisScript<Long> fixedWindowScript
    ) {
        return new RedisFixedWindowRateLimiter(redisTemplate, fixedWindowScript);
    }

    /**
     * 캐시 전용 ObjectMapper.
     *
     * <p>Tenant 등 도메인 객체의 JSON 직렬화/역직렬화 담당.
     * Mixin을 통해 도메인 오염 없이 처리.
     */
    @Bean
    public ObjectMapper cacheObjectMapper(){
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        //Mixin 일괄 등록 (추가될 시 CacheMixinRegisterar에 등록해야함)
        CacheMixinRegistrar.registerAll(mapper);

        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
