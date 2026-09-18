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
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 이유: Redis 연결 설정 — <b>독립 2 Cluster</b>(§75). {@link LettuceConnectionFactory} 를 직접 정의한다.
 * 원인: Boot 표준 키는 <b>클러스터를 하나만</b> 표현해 둘을 독립으로 띄울 수 없다 → 커스텀 프로퍼티.
 * 🔴 <b>Sentinel 분기를 코드에서 지웠다</b> — 프로파일로 나누면 <b>"Cluster 에서만 터지는" 결함이 숨을 통로</b>가 생긴다(인프라는 학습 자산으로 보존, §75 D28).
 * 🔑 <b>큐 상태가 아닌 키는 전부 cluster1 이다</b>({@code rl:*}·캐시) — queueId 가 없어 라우팅 대상이
 *    아니고, WAS 마다 다른 클러스터로 가면 버킷·캐시가 갈라지므로 {@code @Primary} 에 고정한다.
 *
 * @author sonix
 */
@Configuration
public class RedisConfig {

    /**
     * 이유: Redis 커맨드 응답 대기 상한. 기본값 60초면 <b>종료 경로에 시한이 없어진다</b>(stop 은 동기다).
     * 문제: 🔴 <b>"재시도하면 회복된다"가 아니다</b> — Lua 가 이미 성공했으면 재시도가 EXISTS 로 떨어져 발행이 스킵되고 <b>DB 에 없는 좀비</b>가 된다. 반경도 <b>청크 하나(최대 500건)</b> 다.
     * 해결: 5초 — 드러나는 실패(5xx)를 SIGKILL 로 인한 검출 불가 유실보다 택했다(창은 60초에도 있었고 5초는 <b>빈도만 바꾼다</b>). 대가는 failover 5~10초를 못 넘는 것이다.
     * 🔧 옛 주석 둘이 낡았다 — 대사는 <b>구현됐지만 탐지만 한다</b>(보정 없음), 그리고
     *    "JDBC socketTimeout 미설정이라 종료 상한이 없다"는 <b>거짓이 됐다</b>(PR #96).
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 이유: 클러스터 토폴로지 갱신 주기.
     * 문제: 🔴 <b>이 설정이 없으면 failover 후 토폴로지가 영영 갱신되지 않는다.</b>
     * 원인: Lettuce 기본값이 주기 갱신 off + 트리거 없음이라 기동 시 읽은 지도를 그대로 들고 간다.
     * 해결: 주기 갱신 + 적응형 트리거를 함께 켠다 — 주기만으로는 그 주기만큼 MOVED 가 이어진다.
     * 🪤 트리거는 즉시가 아니라 {@link #ADAPTIVE_REFRESH_TIMEOUT} 만큼 debounce 된다(조회 폭주 방지).
     */
   private static final Duration TOPOLOGY_REFRESH_PERIOD = Duration.ofSeconds(30);

    /** 적응형 토폴로지 갱신의 debounce 간격(이 시간 안의 중복 트리거는 1회로 합쳐진다). */
    private static final Duration ADAPTIVE_REFRESH_TIMEOUT = Duration.ofSeconds(10);

    /**
     * cluster1 커넥션 팩토리.
     *
     * <p>{@code @Primary}는 필수다. 이 프로젝트에는 {@code StringRedisTemplate} /
     * {@code RedisConnectionFactory}를 <b>타입으로</b> 주입받는 곳이 프로덕션·테스트 양쪽에
     * 널려 있어서, 후보가 둘이 되는 순간 전부 컨텍스트 로딩에 실패한다.
     */
    @Bean
    @Primary
    public LettuceConnectionFactory redisCluster1Factory(
            @Value("${queue.redis.cluster1.nodes:127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005,127.0.0.1:7006,127.0.0.1:7007,127.0.0.1:7008}") String nodes,
            @Value("${queue.redis.password:}") String password) {
        return clusterFactory(nodes, password);
    }

    /** cluster2 커넥션 팩토리. cluster1과 <b>완전히 독립</b>이며 슬롯을 공유하지 않는다. */
    @Bean
    public LettuceConnectionFactory redisCluster2Factory(
            @Value("${queue.redis.cluster2.nodes:127.0.0.1:8001,127.0.0.1:8002,127.0.0.1:8003,127.0.0.1:8004,127.0.0.1:8005,127.0.0.1:8006,127.0.0.1:8007,127.0.0.1:8008}") String nodes,
            @Value("${queue.redis.password:}") String password) {
        return clusterFactory(nodes, password);
    }

    private static LettuceConnectionFactory clusterFactory(String nodes, String password) {
        RedisClusterConfiguration cluster = new RedisClusterConfiguration(
                Arrays.stream(nodes.split(",")).map(String::trim).toList());
        if (password != null && !password.isBlank()) {
            cluster.setPassword(password);
        }

        ClusterTopologyRefreshOptions refresh = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(TOPOLOGY_REFRESH_PERIOD)
                .enableAllAdaptiveRefreshTriggers()
                .adaptiveRefreshTriggersTimeout(ADAPTIVE_REFRESH_TIMEOUT)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .clientOptions(ClusterClientOptions.builder()
                        .topologyRefreshOptions(refresh)
                        .build())
                .build();

        return new LettuceConnectionFactory(cluster, clientConfig);
    }

    /**
     * cluster1 문자열 템플릿. Rate Limiter의 INCR/EXPIRE, Lua EVAL 등에 사용.
     *
     * <p>빈 이름을 {@code stringRedisTemplate}으로 유지한다 — 기존 주입 지점이 전부
     * 타입 주입이라 {@code @Primary}만으로 해결되지만, 이름까지 바꾸면 이름 기반 주입이
     * 하나라도 있을 때 조용히 깨진다.
     */
    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("redisCluster1Factory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /** cluster2 문자열 템플릿. 큐 단위 라우팅({@code RedisQueueEngine})에서만 쓴다. */
    @Bean
    public StringRedisTemplate cluster2StringRedisTemplate(
            @Qualifier("redisCluster2Factory") LettuceConnectionFactory connectionFactory) {
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
    public RedisScript<List> admitScript() {
        return loadScript("lua/admit.lua", List.class);
    }

    @Bean
    public RedisScript<List> admitExpireScript() {
        return loadScript("lua/admit_expire.lua", List.class);
    }

    /** inactiveTtl 초과 대기자 회수 (§82). 이탈 회수의 유일한 경로다. */
    @Bean
    public RedisScript<List> inactiveExpireScript() {
        return loadScript("lua/inactive_expire.lua", List.class);
    }

    /** waitingTtl(절대 만료) 초과 대기자 회수. §82 구멍 ③(첫 폴링 전 이탈)의 마지노선이다. */
    @Bean
    public RedisScript<List> waitingExpireScript() {
        return loadScript("lua/waiting_expire.lua", List.class);
    }

    /**
     * complete 뒤 Redis 정리. 사람 키(identifier)로 지우는 둘만 회차(tokenId)를 대조한다 —
     * 대조 없이 지우면 늦은 complete가 재-enqueue한 다음 회차를 축출한다.
     */
    @Bean
    public RedisScript<Long> cleanupCompletedScript() {
        return loadScript("lua/cleanup_completed.lua", Long.class);
    }

    @Bean
    public RateLimiter rateLimiter(
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
            @Qualifier("tokenBucketScript") RedisScript<Long> tokenBucketScript
    ) {
        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                tokenBucketScript
        );
    }

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter(
            @Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
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
