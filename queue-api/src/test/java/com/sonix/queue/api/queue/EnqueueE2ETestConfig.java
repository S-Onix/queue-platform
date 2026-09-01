package com.sonix.queue.api.queue;

import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.config.RedisConfig;
import com.sonix.queue.infrastructure.queue.BatchProcessor;
import com.sonix.queue.infrastructure.queue.RedisQueueEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enqueue E2E 통합 테스트 전용 부트스트랩.
 *
 * <p>{@link com.sonix.queue.api.queue.QueueEngineService}(소유권·활성 검증) →
 * {@link RedisQueueEngine}(Global Queue) → {@link BatchProcessor}(Bulk Lua) →
 * 실제 Redis 로 이어지는 전체 흐름을 검증하기 위한 최소 구성이다.
 *
 * <p>실제 관리 API(JPA/Security)를 끌어오지 않도록 컴포넌트 스캔 대신 필요한 빈만
 * 직접 등록한다. {@link RedisConfig}가 StringRedisTemplate / enqueueBulkScript 를 제공하고,
 * Tenant/Queue Repository 는 아래 인메모리 스텁으로 대체한다(=DB 없이 "Tenant 생성 → Queue 생성").
 *
 * <p><b>주의: {@code @SpringBootConfiguration}을 붙이지 말 것.</b> Spring Boot는 테스트 클래스의
 * 패키지에서 위로 올라가며 {@code @SpringBootConfiguration}을 찾는데, 이 클래스가 그렇게 표시되면
 * 같은 패키지의 {@code QueueControllerTest}가 진짜 앱 클래스({@code QueueApiApplication}) 대신
 * 이 클래스를 부트스트랩 클래스로 집어간다. 컴포넌트 스캔이 없으므로 컨트롤러가 등록되지 않아
 * 모든 요청이 404가 된다. 사용하는 테스트가 {@code @SpringBootTest(classes = ...)}로 명시 지정하므로
 * 이 어노테이션은 필요 없다.
 */
@Configuration
@Import(RedisConfig.class)
public class EnqueueE2ETestConfig {

    @Bean
    public RedisQueueEngine redisQueueEngine(
            StringRedisTemplate redisTemplate,
            @Qualifier("enqueueBulkScript") RedisScript<List> enqueueBulkScript,
            @Qualifier("pollVerifyScript") RedisScript<Long> pollVerifyScript,
            @Qualifier("admitScript") RedisScript<List> admitScript,
            @Qualifier("admitExpireScript") RedisScript<List> admitExpireScript,
            @Qualifier("inactiveExpireScript") RedisScript<List> inactiveExpireScript,
            @Qualifier("waitingExpireScript") RedisScript<List> waitingExpireScript,
            @Qualifier("cleanupCompletedScript") RedisScript<Long> cleanupCompletedScript
    ) {
        return new RedisQueueEngine(redisTemplate, enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript, inactiveExpireScript, waitingExpireScript, cleanupCompletedScript);
    }

    @Bean
    public BatchProcessor batchProcessor(RedisQueueEngine engine, QueueRepository queueRepository) {
        return new BatchProcessor(engine, queueRepository);
    }

    @Bean
    public QueueEngineService queueEngineService(QueueRepository queueRepository, RedisQueueEngine engine,
                                                 EnqueueEventPublisher eventPublisher) {
        // TokenRepository는 admit/verify/complete 전용이다. 이 config는 enqueue 흐름(Redis)만
        // 태우므로 JPA 컨텍스트를 끌어오지 않는다 → null.
        return new QueueEngineService(queueRepository, null, engine, eventPublisher,
                java.time.Clock.systemUTC());
    }

    /**
     * no-op 발행자. Enqueue 흐름(Redis) 검증에 집중하기 위해 Kafka 브로커를 띄우지 않는다.
     * (Kafka 발행/소비의 실제 동작은 별도 통합 테스트에서 검증)
     */
    @Bean
    public EnqueueEventPublisher enqueueEventPublisher() {
        return event -> { /* no-op */ };
    }

    /**
     * 인메모리 TenantRepository. save 시 id를 부여(=DB 자동 채번 흉내).
     */
    @Bean
    public TenantRepository tenantRepository() {
        return new TenantRepository() {
            private final Map<Long, Tenant> byId = new ConcurrentHashMap<>();
            private final Map<String, Tenant> byEmail = new ConcurrentHashMap<>();
            private final AtomicLong seq = new AtomicLong(0);

            @Override
            public Tenant save(Tenant tenant) {
                Long id = tenant.getId() != null ? tenant.getId() : seq.incrementAndGet();
                Tenant persisted = Tenant.reconstruct(
                        id, tenant.getTenantId(), tenant.getEmail(), tenant.getPasswordHash(),
                        tenant.getName(), tenant.getStatus(), tenant.getPlan(), tenant.getCreatedAt());
                byId.put(id, persisted);
                byEmail.put(persisted.getEmail(), persisted);
                return persisted;
            }

            @Override public Optional<Tenant> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
            @Override public Optional<Tenant> findByTenantId(String tenantId) {
                return byId.values().stream().filter(t -> t.getTenantId().equals(tenantId)).findFirst();
            }
            @Override public Optional<Tenant> findByEmail(String email) { return Optional.ofNullable(byEmail.get(email)); }
            @Override public boolean existsByEmail(String email) { return byEmail.containsKey(email); }
        };
    }

    /**
     * 인메모리 QueueRepository. save 시 id를 부여하고 queueId로 조회 가능.
     */
    @Bean
    public QueueRepository queueRepository() {
        return new QueueRepository() {
            private final Map<String, Queue> byQueueId = new ConcurrentHashMap<>();
            private final AtomicLong seq = new AtomicLong(0);

            @Override
            public Queue save(Queue queue) {
                Long id = queue.getId() != null ? queue.getId() : seq.incrementAndGet();
                Queue persisted = Queue.reconstruct(
                        id, queue.getQueueId(), queue.getTenantId(), queue.getName(),
                        queue.getMaxCapacity(), queue.getWaitingTtl(),
                        queue.getInactiveTtl(), queue.getStatus(), queue.getCreatedAt(), queue.getDeletedAt());
                byQueueId.put(persisted.getQueueId(), persisted);
                return persisted;
            }

            @Override public Optional<Queue> findByQueueId(String queueId) {
                return Optional.ofNullable(byQueueId.get(queueId));
            }
            @Override public Optional<Queue> findById(Long id) {
                return byQueueId.values().stream().filter(q -> id.equals(q.getId())).findFirst();
            }
            @Override public List<Queue> findAllByTenantId(Long tenantId) {
                return byQueueId.values().stream().filter(q -> tenantId.equals(q.getTenantId())).toList();
            }
            @Override public List<Queue> findAll() {
                return List.copyOf(byQueueId.values());
            }
            @Override public boolean existsByTenantIdAndName(Long tenantId, String name) {
                return byQueueId.values().stream()
                        .anyMatch(q -> tenantId.equals(q.getTenantId()) && q.getName().equals(name));
            }
        };
    }
}
