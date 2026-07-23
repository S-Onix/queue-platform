package com.sonix.queue.batch.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token Enqueue E2E 스모크 테스트 (실제 Kafka + 실제 MySQL).
 *
 * <p><b>이음새 검증</b>: 지금까지 구간별(발행측 / Consumer 매핑 / 어댑터-DB)로 나눠 검증했던 것을
 * 하나로 잇는다 — {@code enqueue-events} 토픽에 이벤트를 <b>실제 발행</b>하면,
 * batch의 {@code @KafkaListener}(배치 모드)가 <b>실제로 깨어나</b> 역직렬화 → 매핑 →
 * {@code tokens} 테이블에 row가 <b>적재</b>되는지 본다. batch 앱이 JPA를 켠 채 정상 부팅되는지도 함께 검증.
 *
 * <p><b>격리/타이밍</b>:
 * <ul>
 *   <li>consumer group-id를 매 실행 유니크하게(@DynamicPropertySource) → 커밋된 오프셋 간섭 없음</li>
 *   <li>{@code auto-offset-reset=latest} + <b>파티션 할당을 기다린 뒤 발행</b> → 토픽의 과거(스테일)
 *       메시지를 안 건드리고 우리 메시지만 소비 (FK 없는 스테일 메시지로 인한 flaky 방지)</li>
 *   <li>비동기라 DB row 등장까지 폴링(최대 30초)</li>
 * </ul>
 *
 * <p>전제: 로컬 Kafka(9092/9094/9096) + MySQL(3306) 기동. tokens→queues→tenants FK 때문에
 * tenant·queue를 @BeforeAll에 시드하고 @AfterAll에 정리한다.
 */
@SpringBootTest(classes = TokenEnqueueE2ETestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
                "spring.datasource.master.username=queueapp",
                "spring.datasource.master.password=queueapp1234",
                "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
                "spring.datasource.replica.username=queueapp",
                "spring.datasource.replica.password=queueapp1234",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",

                "spring.kafka.bootstrap-servers=localhost:9092,localhost:9094,localhost:9096",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
                "spring.kafka.consumer.auto-offset-reset=latest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.properties.spring.json.trusted.packages=com.sonix.queue.*",
                "spring.kafka.listener.type=batch",
                "spring.kafka.listener.ack-mode=manual"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenEnqueueE2ESmokeTest {

    private static final String TOPIC = "enqueue-events";
    private static final String TENANT_KEY = "t_e2e_token";
    private static final String QUEUE_ID = "q_e2e_token";

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;

    private long tenantId;

    /** 매 실행 유니크 group-id → 이전 실행의 커밋 오프셋 간섭 제거. */
    @DynamicPropertySource
    static void uniqueConsumerGroup(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.consumer.group-id", () -> "token-e2e-" + System.currentTimeMillis());
    }

    @BeforeAll
    void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                TENANT_KEY, "e2e_token@test.local", "x", "e2e");
        tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, TENANT_KEY);
        jdbc.update("INSERT IGNORE INTO queues (queue_id, tenant_id, name, max_capacity) VALUES (?, ?, ?, ?)",
                QUEUE_ID, tenantId, "e2e-queue", 100000);
    }

    @AfterAll
    void cleanupFixtures() {
        jdbc.update("DELETE FROM tokens WHERE queue_id = ?", QUEUE_ID);
        jdbc.update("DELETE FROM queues WHERE queue_id = ?", QUEUE_ID);
        jdbc.update("DELETE FROM tenants WHERE tenant_id = ?", TENANT_KEY);
    }

    @Test
    @DisplayName("enqueue-events 발행 → @KafkaListener 소비 → tokens 테이블에 row 적재까지 이어진다")
    void publish_thenRowLandsInTokens() throws Exception {
        awaitListenerAssignment(Duration.ofSeconds(20)); // latest이므로 할당 후 발행분만 소비됨

        String tokenId = "tok_e2e_" + UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-07-15T01:00:00Z");
        EnqueueEvent event = new EnqueueEvent(tokenId, QUEUE_ID, tenantId, "user_e2e", 777L, issuedAt);

        // 실제 발행 (전송 확정까지 대기)
        kafkaTemplate.send(TOPIC, QUEUE_ID, event).get(10, TimeUnit.SECONDS);

        // 비동기 소비 → DB에 row 등장까지 폴링
        boolean appeared = awaitRow(tokenId, Duration.ofSeconds(30));
        assertThat(appeared).as("발행한 이벤트가 tokens 테이블에 적재됨").isTrue();

        // 값 왕복 검증
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, user_id, seq, status, issued_at FROM tokens WHERE token_id = ?", tokenId);
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(row.get("user_id")).isEqualTo("user_e2e");
        assertThat(((Number) row.get("seq")).longValue()).isEqualTo(777L);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(0); // WAITING
        Object issued = row.get("issued_at");
        LocalDateTime storedIssuedAt = (issued instanceof Timestamp ts) ? ts.toLocalDateTime() : (LocalDateTime) issued;
        assertThat(storedIssuedAt)
                .as("Instant(UTC 01:00) → Consumer가 UTC LocalDateTime으로 저장")
                .isEqualTo(LocalDateTime.of(2026, 7, 15, 1, 0, 0));
    }

    // ---------------------------------------------------------------------

    /** 리스너 컨테이너들이 파티션을 할당받을 때까지 대기 (그 전에 발행하면 latest라 유실). */
    private void awaitListenerAssignment(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            boolean allAssigned = !containers.isEmpty() && containers.stream().allMatch(c -> {
                Collection<TopicPartition> parts = c.getAssignedPartitions();
                return parts != null && !parts.isEmpty();
            });
            if (allAssigned) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("리스너 파티션 할당 대기 시간 초과 — Kafka 기동 확인 필요");
    }

    /** token_id로 tokens에 row가 나타날 때까지 폴링. */
    private boolean awaitRow(String tokenId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE token_id = ?", Integer.class, tokenId);
            if (n != null && n > 0) return true;
            Thread.sleep(500);
        }
        return false;
    }
}
