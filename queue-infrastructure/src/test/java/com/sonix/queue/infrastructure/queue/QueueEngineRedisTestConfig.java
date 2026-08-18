package com.sonix.queue.infrastructure.queue;

import org.springframework.context.annotation.Bean;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.infrastructure.config.RedisConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Queue Engine 동시성 통합 테스트 전용 부트스트랩 설정.
 *
 * <p>{@link com.sonix.queue.infrastructure.ratelimit.RateLimitRedisTestConfig}와 동일 취지:
 * infra 모듈엔 앱 클래스가 없으므로 @SpringBootConfiguration을 테스트 소스에 둔다.
 * JPA/Kafka 오토컨피그를 피하기 위해 @EnableAutoConfiguration 대신 필요한 빈만 @Import한다.
 *
 * <p>RedisConfig가 StringRedisTemplate, enqueueBulkScript 등을 제공하고,
 * 여기서 RedisQueueEngine / BatchProcessor / QueueRepository(스텁) 빈을 추가한다.
 *
 * <p><b>QueueRepository 스텁:</b> BatchProcessor.getMaxCapacity가 findByQueueId를 호출하므로,
 * DB 없이 항상 큰 capacity의 ACTIVE 큐를 반환하는 스텁을 제공한다.
 * (정원 초과 테스트에서 작은 capacity가 필요하면 이 스텁을 조정)
 */
@SpringBootConfiguration
@Import(RedisConfig.class)
public class QueueEngineRedisTestConfig {

    @Bean
    public RedisQueueEngine redisQueueEngine(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("enqueueBulkScript")
            org.springframework.data.redis.core.script.RedisScript<List> enqueueBulkScript,
            @org.springframework.beans.factory.annotation.Qualifier("pollVerifyScript")
            org.springframework.data.redis.core.script.RedisScript<Long> pollVerifyScript,
            @org.springframework.beans.factory.annotation.Qualifier("admitScript")
            org.springframework.data.redis.core.script.RedisScript<List> admitScript,
            @org.springframework.beans.factory.annotation.Qualifier("admitExpireScript")
            org.springframework.data.redis.core.script.RedisScript<List> admitExpireScript
    ) {
        return new RedisQueueEngine(redisTemplate, enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript);
    }

    @Bean
    public BatchProcessor batchProcessor(RedisQueueEngine engine, QueueRepository queueRepository) {
        return new BatchProcessor(engine, queueRepository);
    }

    @Bean
    public QueueRepository queueRepository() {
        return new QueueRepository() {
            @Override
            public Optional<Queue> findByQueueId(String queueId) {
                // 테스트용: 항상 큰 capacity의 ACTIVE 큐
                return Optional.of(Queue.reconstruct(
                        1L, queueId, 1L, "테스트큐", 100000, 7200, 300,
                        QueueStatus.ACTIVE, LocalDateTime.now(), null
                ));
            }

            @Override public Queue save(Queue queue) { throw new UnsupportedOperationException(); }
            @Override public Optional<Queue> findById(Long id) { return Optional.empty(); }
            @Override public List<Queue> findAllByTenantId(Long tenantId) { return List.of(); }
            @Override public List<Queue> findAll() { return List.of(); }
            @Override public boolean existsByTenantIdAndName(Long tenantId, String name) { return false; }
        };
    }
}