package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.kafka.support.serializer.JsonDeserializer.USE_TYPE_INFO_HEADERS;
import static org.springframework.kafka.support.serializer.JsonDeserializer.VALUE_DEFAULT_TYPE;
import static org.springframework.kafka.support.serializer.JsonSerializer.ADD_TYPE_INFO_HEADERS;

/**
 * 이벤트가 Kafka 직렬화를 왕복해도 동일한지 검증한다.
 *
 * <p><b>왜 이 테스트가 필요한가:</b> {@code issuedAt}은 {@code UNIQUE (token_id, issued_at)}의
 * 절반이다. 직렬화 과정에서 1ms라도 어긋나면 재처리 시 <b>같은 토큰이 다른 행으로 들어가</b>
 * 멱등 적재가 무력화된다. 예외가 나지 않고 조용히 행이 늘어나는 유형이라 테스트로 고정한다.
 *
 * <p>프로듀서·컨슈머 설정이 어긋나는 것도 여기서 잡는다 — 한쪽만 타입 헤더를 쓰면
 * 역직렬화가 엉뚱한 타입으로 시도된다.
 */
class EnqueueEventSerdeTest {

    private static final String TOPIC = "token-lifecycle";

    /** queue-api의 producer 설정과 동일하게 맞춘다. */
    private static JsonSerializer<Object> serializer() {
        JsonSerializer<Object> serializer = new JsonSerializer<>();
        serializer.configure(Map.of(ADD_TYPE_INFO_HEADERS, false), false);
        return serializer;
    }

    /** queue-consumer의 consumer 설정과 동일하게 맞춘다. */
    private static JsonDeserializer<EnqueueEvent> deserializer() {
        JsonDeserializer<EnqueueEvent> deserializer = new JsonDeserializer<>();
        deserializer.configure(Map.of(
                USE_TYPE_INFO_HEADERS, false,
                VALUE_DEFAULT_TYPE, EnqueueEvent.class.getName()), false);
        return deserializer;
    }

    private static EnqueueEvent roundTrip(EnqueueEvent original) {
        try (JsonSerializer<Object> serializer = serializer();
             JsonDeserializer<EnqueueEvent> deserializer = deserializer()) {
            return deserializer.deserialize(TOPIC, serializer.serialize(TOPIC, original));
        }
    }

    @Test
    @DisplayName("밀리초 정밀도의 issuedAt이 왕복 후에도 정확히 보존된다")
    void preservesMillisecondPrecision() {
        // tokens.issued_at 이 DATETIME(3) 이므로 밀리초까지가 저장 대상이다.
        Instant issuedAt = Instant.parse("2026-08-10T12:34:56.789Z");
        EnqueueEvent original = new EnqueueEvent("tok_a1b2", "q_ticket", 42L, "user-1", 1234L, issuedAt);

        EnqueueEvent restored = roundTrip(original);

        assertThat(restored.issuedAt()).isEqualTo(issuedAt);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("큰 seq 값이 정밀도 손실 없이 보존된다")
    void preservesLargeSeq() {
        // 과학표기·double 변환이 끼어들면 큰 정수가 뭉개진다. Long 경계로 확인한다.
        EnqueueEvent original = new EnqueueEvent(
                "tok_big", "q_ticket", Long.MAX_VALUE, "user-1",
                Long.MAX_VALUE, Instant.parse("2026-08-10T00:00:00.001Z"));

        EnqueueEvent restored = roundTrip(original);

        assertThat(restored.seq()).isEqualTo(Long.MAX_VALUE);
        assertThat(restored.tenantId()).isEqualTo(Long.MAX_VALUE);
        assertThat(restored).isEqualTo(original);
    }
}
