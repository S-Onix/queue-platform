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
 * Redis 연결 설정 (독립 2 Cluster, DECISIONS §75).
 *
 * <p>RedisAutoConfiguration은 application.yml에서 여전히 제외 상태이므로,
 * 여기서 명시적으로 {@link LettuceConnectionFactory}를 정의한다 (학습/제어 목적).
 *
 * <p><b>왜 커스텀 프로퍼티({@code queue.redis.cluster1/2.nodes})인가:</b>
 * Boot 표준 키 {@code spring.data.redis.cluster.nodes}는 <b>클러스터를 하나만</b> 표현한다.
 * 두 개를 독립으로 띄우는 구성은 표준 키로 표현할 방법이 없다.
 *
 * <p><b>Sentinel 설정은 제거했다.</b> 프로파일로 분기하지 않는다 — 해시태그 누락처럼
 * "Cluster에서만 터지는" 결함이 Sentinel 경로로 숨을 통로를 남기지 않기 위함이다.
 * Sentinel 인프라·문서 자체는 학습/로컬 자산으로 보존한다(§75 D28).
 *
 * <p><b>큐 상태가 아닌 키는 전부 cluster1에 있다.</b> {@code rl:*}(Rate Limit),
 * {@code apikey-cache:*}, {@code tenant-cache:*}는 queueId가 없어 라우팅 대상이 아니다.
 * 이들이 WAS마다 다른 클러스터로 가면 버킷·캐시가 갈라지므로, {@code @Primary}(cluster1)에
 * 고정한다.
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
     * 이 시한에 걸려 포기하면, 사용자가 재시도해도 {@code enqueue_bulk.lua}의 {@code HSETNX}가
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

    /**
     * 클러스터 토폴로지 갱신 주기.
     *
     * <p><b>이 설정이 없으면 failover 후 토폴로지가 영영 갱신되지 않는다.</b> Lettuce의
     * {@code ClusterClientOptions} 기본값은 {@code periodicRefreshEnabled=false} +
     * {@code adaptiveRefreshTriggers=emptySet()}이라, 기동 시 한 번 읽은 슬롯→노드 지도를
     * 그대로 들고 간다. Sentinel 구성에서는 Sentinel이 대신하던 일이라 코드가 필요 없었지만,
     * Cluster에서는 클라이언트가 직접 해야 한다.
     *
     * <p>주기 갱신만으로는 최대 이 주기만큼 MOVED/실패가 이어지므로 적응형 트리거
     * (MOVED/ASK 재지정, 연결 끊김, 재연결 시도 등)를 함께 켠다. 트리거는 즉시가 아니라
     * {@link #ADAPTIVE_REFRESH_TIMEOUT} 만큼 debounce되어, 대량 MOVED가 몰려도
     * 토폴로지 조회가 폭주하지 않는다.
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
