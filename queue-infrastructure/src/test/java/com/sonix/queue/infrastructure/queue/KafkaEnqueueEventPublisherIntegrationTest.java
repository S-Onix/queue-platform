package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KafkaEnqueueEventPublisher 통합 테스트 (실제 브로커, localhost:9092).
 *
 * <p><b>검증 명제:</b>
 * <ul>
 *   <li>Boot 오토컨피그가 만든 KafkaTemplate이 publisher에 실제 주입된다(DI)</li>
 *   <li>EnqueueEvent가 JsonSerializer로 직렬화되어 실제로 발행된다(특히 {@code Instant issuedAt})</li>
 *   <li>되받은 이벤트의 모든 필드가 온전히 왕복한다</li>
 * </ul>
 *
 * <p>토픽은 프로덕션과 같은 {@code enqueue-events}를 쓰므로, 기존/타 테스트 메시지와 섞이지 않도록
 * (1) 소비 시작 전 {@code seekToEnd}로 과거 메시지를 건너뛰고, (2) 이 테스트만의 유일한 tokenId로
 * 매칭한다. 브로커가 떠 있어야 하며, {@code enqueue-events} 자동 생성이 켜져 있어야 한다.
 */
@SpringBootTest(classes = KafkaPublisherTestConfig.class, properties = {
        "spring.kafka.bootstrap-servers=localhost:9092,localhost:9094,localhost:9096",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true"
})
class KafkaEnqueueEventPublisherIntegrationTest {

    private static final String TOPIC = "enqueue-events";
    private static final String BOOTSTRAP = "localhost:9092,localhost:9094,localhost:9096";

    @Autowired
    private KafkaEnqueueEventPublisher publisher;

    @Test
    @DisplayName("EnqueueEvent를 실제 브로커로 발행하면 모든 필드(Instant 포함)가 온전히 왕복한다")
    void publish_roundTrip() {
        // given: 이 테스트만의 유일한 tokenId (기존 메시지와 구분)
        String tokenId = "tok_it_" + UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-07-23T01:23:45.678Z");
        EnqueueEvent event = new EnqueueEvent(tokenId, "q_it_1", 42L, "user_it_1", 100L, issuedAt);

        try (KafkaConsumer<String, EnqueueEvent> consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            awaitAssignmentAndSeekToEnd(consumer);   // 과거 메시지 스킵 + 위치를 "발행 전 끝"으로 확정

            // when
            publisher.publish(event);

            // then
            EnqueueEvent received = pollFor(consumer, tokenId);
            assertThat(received).as("우리 tokenId의 이벤트를 되받음").isNotNull();
            assertThat(received.tokenId()).isEqualTo(tokenId);
            assertThat(received.queueId()).isEqualTo("q_it_1");
            assertThat(received.tenantId()).isEqualTo(42L);
            assertThat(received.userId()).isEqualTo("user_it_1");
            assertThat(received.seq()).isEqualTo(100L);
            // Instant 왕복 — 직렬화 표현(ISO/epoch) 무관하게 millis 정밀도로 검증
            assertThat(received.issuedAt().toEpochMilli()).isEqualTo(issuedAt.toEpochMilli());
        }
    }

    /**
     * 파티션 할당(최대 5초) 후 끝으로 seek하고, 그 위치를 <b>즉시 확정</b>한다.
     *
     * <p>{@code seekToEnd}는 lazy라 다음 poll에서야 평가된다. 발행 전에 position()을 호출해
     * 지금 시점의 end offset을 강제로 조회·고정해야, 이후 발행한 메시지를 건너뛰지 않는다.
     */
    private void awaitAssignmentAndSeekToEnd(KafkaConsumer<String, EnqueueEvent> consumer) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200));
        }
        assertThat(consumer.assignment()).as("파티션 할당됨").isNotEmpty();
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);   // end offset을 지금 확정
    }

    /** tokenId가 일치하는 이벤트를 최대 10초 폴링해 반환 (없으면 null). */
    private EnqueueEvent pollFor(KafkaConsumer<String, EnqueueEvent> consumer, String tokenId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, EnqueueEvent> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, EnqueueEvent> rec : records) {
                if (rec.value() != null && tokenId.equals(rec.value().tokenId())) {
                    return rec.value();
                }
            }
        }
        return null;
    }

    private KafkaConsumer<String, EnqueueEvent> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        // 헤더 타입 정보 대신 고정 타입으로 역직렬화 (결정적)
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, EnqueueEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.sonix.queue.*");
        return new KafkaConsumer<>(props);
    }
}
