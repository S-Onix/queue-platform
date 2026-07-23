package com.sonix.queue.batch.token;

import com.sonix.queue.infrastructure.adapter.TokenJpaAdapter;
import com.sonix.queue.infrastructure.config.DataSourceConfig;
import com.sonix.queue.infrastructure.config.JpaConfig;
import com.sonix.queue.infrastructure.config.RedisConfig;
import com.sonix.queue.infrastructure.queue.KafkaEnqueueEventPublisher;
import com.sonix.queue.infrastructure.queue.RedisQueueEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * Token Enqueue 부하 테스트 전용 부트스트랩 (실제 Redis + Kafka + MySQL).
 *
 * <p>전체 파이프라인을 한 컨텍스트에 올린다:
 * <ul>
 *   <li>{@link RedisConfig} — StringRedisTemplate + enqueueBulkScript (Sentinel 연결)</li>
 *   <li>{@link RedisQueueEngine} — enqueue_bulk.lua 실행 (ZADD NX + INCR seq + token)</li>
 *   <li>{@link KafkaEnqueueEventPublisher} — enqueue-events 발행 (프로덕션 그대로)</li>
 *   <li>JPA(+DataSource) + {@link TokenJpaAdapter}/{@link TokenEnqueueService}/{@link TokenEnqueueConsumer}
 *       — Kafka → MySQL 적재 (프로덕션 그대로)</li>
 * </ul>
 *
 * <p>BatchProcessor/QueueRepository는 안 띄운다 — 부하 생성기가 {@code executeBulkLua}를 직접
 * 호출하고 maxCapacity를 인자로 주므로 Global Queue 버퍼링/스케줄러는 우회한다(동일 Lua 실행).
 */
@SpringBootConfiguration
@EnableConfigurationProperties
@ImportAutoConfiguration({
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
@Import({
        RedisConfig.class,
        DataSourceConfig.class,
        JpaConfig.class,
        TokenJpaAdapter.class,
        TokenEnqueueService.class,
        TokenEnqueueConsumer.class
})
public class TokenEnqueueLoadTestConfig {

    @Bean
    public RedisQueueEngine redisQueueEngine(
            StringRedisTemplate redisTemplate,
            @Qualifier("enqueueBulkScript") RedisScript<List> enqueueBulkScript) {
        return new RedisQueueEngine(redisTemplate, enqueueBulkScript);
    }

    @Bean
    public KafkaEnqueueEventPublisher kafkaEnqueueEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaEnqueueEventPublisher(kafkaTemplate);
    }
}
