package com.sonix.queue.infrastructure.token;

import com.sonix.queue.infrastructure.adapter.BillingJdbcAdapter;
import com.sonix.queue.infrastructure.adapter.TokenJpaAdapter;
import com.sonix.queue.infrastructure.config.DataSourceConfig;
import com.sonix.queue.infrastructure.config.JpaConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * TokenJpaAdapter 통합 테스트 전용 부트스트랩 (실제 MySQL).
 *
 * <p>{@link com.sonix.queue.infrastructure.ratelimit.RateLimitRedisTestConfig}와 동일 취지 —
 * infra 모듈엔 앱 클래스가 없으므로 @SpringBootConfiguration을 테스트 소스에 둔다.
 * 다만 여기선 JPA/DataSource가 필요하므로, 전체 오토컨피그 대신 <b>DB에 필요한 최소 오토컨피그</b>만
 * {@code @ImportAutoConfiguration}으로 끌어온다:
 * <ul>
 *   <li>{@link HibernateJpaAutoConfiguration} — EntityManagerFactory + JpaTransactionManager</li>
 *   <li>{@link TransactionAutoConfiguration} — @Transactional 활성화</li>
 *   <li>{@link JdbcTemplateAutoConfiguration} — 검증용 JdbcTemplate</li>
 * </ul>
 *
 * <p>{@code DataSourceAutoConfiguration}은 일부러 안 넣는다 — DataSource는 프로덕션과 동일하게
 * {@link DataSourceConfig}(Master/Replica 라우팅)가 직접 만든다. {@link JpaConfig}가
 * @EnableJpaRepositories/@EntityScan로 TokenJpaRepository·TokenEntity를 등록한다.
 * {@code @EnableConfigurationProperties}는 DataSourceConfig의 @ConfigurationProperties @Bean
 * 바인딩(spring.datasource.master/replica)을 활성화하기 위함.
 *
 * <p>datasource 접속 정보·ddl-auto는 테스트 클래스의 {@code @SpringBootTest(properties=...)}에서 준다.
 */
@SpringBootConfiguration
@EnableConfigurationProperties
@ImportAutoConfiguration({
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
@Import({DataSourceConfig.class, JpaConfig.class, TokenJpaAdapter.class, BillingJdbcAdapter.class})
public class TokenJpaTestConfig {
}
