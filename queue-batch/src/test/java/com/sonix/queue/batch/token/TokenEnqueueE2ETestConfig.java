package com.sonix.queue.batch.token;

import com.sonix.queue.infrastructure.adapter.TokenJpaAdapter;
import com.sonix.queue.infrastructure.config.DataSourceConfig;
import com.sonix.queue.infrastructure.config.JpaConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Token Enqueue E2E 스모크 테스트 전용 부트스트랩.
 *
 * <p>batch 앱 전체(QueueBatchApplication) 대신, 이 흐름에 필요한 최소 조각만 띄운다 —
 * Redis/웹 없이 <b>JPA + Kafka + 실제 Consumer/Service/Adapter</b>만:
 * <ul>
 *   <li>{@link HibernateJpaAutoConfiguration}/{@link TransactionAutoConfiguration}/{@link JdbcTemplateAutoConfiguration} — DB</li>
 *   <li>{@link KafkaAutoConfiguration} — 리스너 컨테이너 팩토리 + KafkaTemplate + @KafkaListener 활성화</li>
 *   <li>{@link DataSourceConfig}/{@link JpaConfig} — Master/Replica 라우팅 + TokenEntity/TokenJpaRepository 등록</li>
 *   <li>{@link TokenJpaAdapter}/{@link TokenEnqueueService}/{@link TokenEnqueueConsumer} — 실제 프로덕션 빈</li>
 * </ul>
 *
 * <p>datasource·kafka 접속 정보는 테스트 클래스의 {@code @SpringBootTest(properties=...)}에서 준다.
 * Redis 오토컨피그를 안 끌어오므로 이 테스트는 Redis 기동이 필요 없다.
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
        DataSourceConfig.class,
        JpaConfig.class,
        TokenJpaAdapter.class,
        TokenEnqueueService.class,
        TokenEnqueueConsumer.class
})
public class TokenEnqueueE2ETestConfig {
}
