package com.sonix.queue.api.apikey;

import com.sonix.queue.api.security.ApiKeyAuthenticationFilter;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyHasher;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import com.sonix.queue.infrastructure.cache.RedisApiKeyCache;
import com.sonix.queue.infrastructure.cache.RedisKeyFactory;
import com.sonix.queue.infrastructure.config.RedisConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폐기(revoke)한 API Key가 <b>즉시</b> 막히는지를 실제 Redis(cluster1)로 못박는다.
 *
 * <p><b>왜 이 테스트가 필요한가.</b> {@link ApiKeyAuthenticationFilter}는 {@code apikey:{keyHash}}를
 * 먼저 보고(POSITIVE_TTL 60초) 히트하면 DB를 읽지 않는다. 캐시에 든 객체는 revoke 이전 스냅샷이라
 * {@code isActive()}도 통과시킨다 — 상태 검사로는 못 막는다. 그래서 캐시를 지우지 않으면
 * 폐기한 키가 <b>최대 60초 더 통과</b>했다.
 *
 * <p>🔴 <b>순서가 전부다.</b> revoke 전에 <b>인증 경로를 한 번 태워 캐시를 채워야</b> 한다.
 * 캐시가 비어 있으면 {@code invalidate}가 없는(수정 전) 코드로도 "키가 없다"가 성립해
 * 초록이 뜬다 — 그러면 이 테스트는 아무것도 지키지 못한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. queue-api 테스트 소스에는 {@code EnqueueE2ETestConfig}가 있어
 * {@code @SpringBootTest}의 설정 탐색이 꼬이고, 이 검증에 필요한 건 {@code StringRedisTemplate}
 * 하나뿐이라 커넥션 팩토리를 직접 만든다. DB는 쓰지 않는다(in-memory {@link ApiKeyRepository} 대역)
 * — 검증 대상이 캐시 무효화이지 JPA가 아니기 때문이다.
 */
@Tag("redis")
@DisplayName("API Key 폐기 시 Redis 캐시 무효화")
class ApiKeyRevokeCacheEvictionRedisTest {

    private static final String ENGINE_PATH = "/api/v1/queues/q_test_apikey/tokens";
    private static final long TENANT_ID = 90_001L;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisApiKeyCache cache;

    /** 이 테스트가 만든 캐시 키만 지우기 위해 추적한다. 공용 인프라라 패턴 삭제·FLUSHALL 금지. */
    private final List<String> createdKeys = new ArrayList<>();

    private final InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
    private final ApiKeyService service = new ApiKeyService(repository, cache);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(repository, cache);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisClusterConfiguration(List.of(
                "127.0.0.1:7001", "127.0.0.1:7002", "127.0.0.1:7003", "127.0.0.1:7004",
                "127.0.0.1:7005", "127.0.0.1:7006", "127.0.0.1:7007", "127.0.0.1:7008")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        cache = new RedisApiKeyCache(redis, new RedisConfig().cacheObjectMapper());
    }

    @AfterEach
    void cleanUp() {
        createdKeys.forEach(redis::delete);
        createdKeys.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    @DisplayName("캐시가 채워진 상태에서 폐기하면 apikey:{keyHash}가 Redis에서 사라지고 다음 요청이 즉시 거부된다")
    void 폐기하면_캐시가_지워지고_즉시_거부된다() {
        ApiKey apiKey = issue();
        String rawKey = repository.rawKeyOf(apiKey);
        String cacheKey = RedisKeyFactory.apiKey(apiKey.getKeyHash());

        // 인증 경로를 한 번 태워 캐시를 채운다 — 이 단계가 없으면 수정 전 코드로도 통과한다
        assertThat(authenticate(rawKey)).as("폐기 전에는 통과해야 한다").isNotNull();
        assertThat(redis.hasKey(cacheKey)).as("인증 1회로 캐시가 채워져야 한다").isTrue();

        service.revokeApiKey(TENANT_ID, apiKey.getApiKeyId());

        assertThat(redis.hasKey(cacheKey))
                .as("revoke가 apikey:{keyHash}를 지워야 한다. 남아 있으면 폐기가 TTL 60초만큼 지연된다")
                .isFalse();

        // 캐시가 비었으니 DB를 다시 읽어 REVOKED를 본다 → 인증 실패
        assertThat(authenticate(rawKey)).as("폐기 직후 요청은 거부돼야 한다").isNull();
    }

    @Test
    @DisplayName("한 키를 폐기해도 다른 정상 키의 캐시와 인증은 그대로다")
    void 정상_키는_영향을_받지_않는다() {
        ApiKey revoked = issue();
        ApiKey alive = issue();
        String aliveCacheKey = RedisKeyFactory.apiKey(alive.getKeyHash());

        authenticate(repository.rawKeyOf(revoked));
        assertThat(authenticate(repository.rawKeyOf(alive))).isNotNull();
        assertThat(redis.hasKey(aliveCacheKey)).isTrue();

        service.revokeApiKey(TENANT_ID, revoked.getApiKeyId());

        assertThat(redis.hasKey(aliveCacheKey))
                .as("남의 캐시까지 지우면 안 된다 — 무효화 범위는 그 keyHash 하나다")
                .isTrue();
        assertThat(authenticate(repository.rawKeyOf(alive))).as("정상 키는 계속 통과한다").isNotNull();
    }

    /** rawKey로 실제 인증 필터를 태우고, 성공했으면 Authentication을 준다(실패면 null). */
    private Authentication authenticate(String rawKey) {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENGINE_PATH);
        request.addHeader("X-API-Key", rawKey);
        try {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private ApiKey issue() {
        // 접두 it_key_ : 같은 인프라를 쓰는 다른 에이전트와 섞이지 않게 한다.
        // 캐시 키는 rawKey가 아니라 SHA-256 해시라 접두가 드러나지 않으므로, 만든 키를 목록으로 추적해 지운다.
        String rawKey = "it_key_" + UUID.randomUUID();
        ApiKey apiKey = ApiKey.create(TENANT_ID, ApiKeyHasher.hash(rawKey));
        repository.save(apiKey);
        repository.register(rawKey, apiKey);
        createdKeys.add(RedisKeyFactory.apiKey(apiKey.getKeyHash()));
        return apiKey;
    }

    /** DB 대역. */
    private static class InMemoryApiKeyRepository implements ApiKeyRepository {
        private final Map<String, ApiKey> byApiKeyId = new HashMap<>();
        private final Map<String, ApiKey> byKeyHash = new HashMap<>();
        private final Map<String, String> rawByApiKeyId = new HashMap<>();

        void register(String rawKey, ApiKey apiKey) {
            rawByApiKeyId.put(apiKey.getApiKeyId(), rawKey);
        }

        String rawKeyOf(ApiKey apiKey) {
            return rawByApiKeyId.get(apiKey.getApiKeyId());
        }

        @Override
        public ApiKey save(ApiKey apiKey) {
            byApiKeyId.put(apiKey.getApiKeyId(), apiKey);
            byKeyHash.put(apiKey.getKeyHash(), apiKey);
            return apiKey;
        }

        @Override
        public Optional<ApiKey> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<ApiKey> findByApiKeyId(String apiKeyId) {
            return Optional.ofNullable(byApiKeyId.get(apiKeyId));
        }

        @Override
        public Optional<ApiKey> findByKeyHash(String keyHash) {
            return Optional.ofNullable(byKeyHash.get(keyHash));
        }

        @Override
        public List<ApiKey> findAllByTenantId(Long tenantId) {
            return List.copyOf(byApiKeyId.values());
        }
    }
}
