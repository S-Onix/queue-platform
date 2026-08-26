package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.ExpiredReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
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
    @DisplayName("expiredReason이 없던 옛 메시지가 그대로 복원된다 — 롤링 배포 중 큐에 남은 형태다")
    void deserializesMessageWithoutExpiredReason() {
        // 🔑 §86이 EnqueueEvent에 칸을 하나 더했다. 신규 컨슈머가 뜨는 순간 토픽에는
        //    그 칸이 없는 메시지가 남아 있다. 여기서 깨지면 적재가 통째로 멈춘다.
        byte[] legacy = ("""
                {"eventType":"EXPIRED","tokenId":"tok_old","queueId":"q_ticket","tenantId":42,
                 "userId":"user-1","seq":7,"issuedAt":"2026-08-10T12:34:56.789Z",
                 "admitToken":null,"admittedAt":null}
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (JsonDeserializer<EnqueueEvent> deserializer = deserializer()) {
            EnqueueEvent restored = deserializer.deserialize(TOPIC, legacy);

            assertThat(restored.expiredReason()).isNull();   // 사유 미상 — 옛 메시지의 정직한 표현
            assertThat(restored.tokenId()).isEqualTo("tok_old");
            assertThat(restored.seq()).isEqualTo(7L);
        }
    }

    @Test
    @DisplayName("만료 사유가 왕복 후에도 보존된다 — 이 칸이 비면 만료 원인이 영구 소실된다")
    void preservesExpiredReason() {
        EnqueueEvent original = new EnqueueEvent(
                "EXPIRED", "tok_x", "q_ticket", 42L, "user-1", 9L,
                Instant.parse("2026-08-10T12:34:56.789Z"), null, null,
                ExpiredReason.WAITING_TTL.getCode());

        assertThat(roundTrip(original).expiredReason()).isEqualTo(ExpiredReason.WAITING_TTL.getCode());
    }

    @Test
    @DisplayName("밀리초 정밀도의 issuedAt이 왕복 후에도 정확히 보존된다")
    void preservesMillisecondPrecision() {
        // tokens.issued_at 이 DATETIME(3) 이므로 밀리초까지가 저장 대상이다.
        Instant issuedAt = Instant.parse("2026-08-10T12:34:56.789Z");
        EnqueueEvent original = new EnqueueEvent(
                "ENQUEUED", "tok_a1b2", "q_ticket", 42L, "user-1", 1234L, issuedAt, null, null, null);

        EnqueueEvent restored = roundTrip(original);

        assertThat(restored.issuedAt()).isEqualTo(issuedAt);
        assertThat(restored).isEqualTo(original);
    }

    /**
     * <b>하위 호환의 실증.</b> 판별 필드가 생기기 전에 토픽에 쌓인 메시지와, 롤링 배포 중
     * 구 프로듀서가 보내는 메시지에는 이 필드가 없다. {@code ENQUEUED}로 읽지 못하면
     * 백로그 전체가 처리 불가가 된다.
     */
    @Test
    @DisplayName("판별 필드가 없는 구 메시지는 ENQUEUED로 역직렬화된다")
    void readsLegacyMessageAsEnqueued() {
        byte[] legacy = ("{\"tokenId\":\"tok_old\",\"queueId\":\"q_ticket\",\"tenantId\":42,"
                + "\"userId\":\"user-1\",\"seq\":7,\"issuedAt\":\"2026-08-10T12:34:56.789Z\"}")
                .getBytes(StandardCharsets.UTF_8);

        try (JsonDeserializer<EnqueueEvent> deserializer = deserializer()) {
            EnqueueEvent restored = deserializer.deserialize(TOPIC, legacy);

            assertThat(restored.eventType()).isEqualTo("ENQUEUED");
            assertThat(restored.seq()).isEqualTo(7L);
            assertThat(restored.issuedAt()).isEqualTo(Instant.parse("2026-08-10T12:34:56.789Z"));
        }
    }

    /**
     * 모르는 타입 값에서 <b>역직렬화가 터지면 안 된다.</b> 터지면 어느 레코드가 문제인지
     * (배치 안 인덱스)를 잃어 그 한 건만 격리하는 길이 막힌다.
     */
    @Test
    @DisplayName("모르는 판별 필드 값도 역직렬화되어 소비 측이 격리할 수 있다")
    void keepsUnknownEventTypeAsIs() {
        EnqueueEvent original = new EnqueueEvent(
                "WHAT_IS_THIS", "tok_x", "q_ticket", 1L, "user-1", 1L,
                Instant.parse("2026-08-10T00:00:00.001Z"), null, null, null);

        assertThat(roundTrip(original).eventType()).isEqualTo("WHAT_IS_THIS");
    }

    @Test
    @DisplayName("큰 seq 값이 정밀도 손실 없이 보존된다")
    void preservesLargeSeq() {
        // 과학표기·double 변환이 끼어들면 큰 정수가 뭉개진다. Long 경계로 확인한다.
        EnqueueEvent original = new EnqueueEvent(
                "ENQUEUED", "tok_big", "q_ticket", Long.MAX_VALUE, "user-1",
                Long.MAX_VALUE, Instant.parse("2026-08-10T00:00:00.001Z"), null, null, null);

        EnqueueEvent restored = roundTrip(original);

        assertThat(restored.seq()).isEqualTo(Long.MAX_VALUE);
        assertThat(restored.tenantId()).isEqualTo(Long.MAX_VALUE);
        assertThat(restored).isEqualTo(original);
    }

    /**
     * ADMITTED만 갖는 두 칸이 왕복에서 살아남는지. {@code admittedAt}은 verify·complete의
     * 유효 창(60초) 기준이라 밀리초가 뭉개지면 창의 경계가 달라진다.
     */
    @Test
    @DisplayName("ADMITTED의 admitToken·admittedAt이 왕복 후에도 보존된다")
    void preservesAdmitFields() {
        Instant admittedAt = Instant.parse("2026-08-10T12:35:00.123Z");
        EnqueueEvent original = new EnqueueEvent(
                "ADMITTED", "tok_a1b2", "q_ticket", 42L, "user-1", 1234L,
                Instant.parse("2026-08-10T12:34:56.789Z"), "adm_9f3c", admittedAt, null);

        EnqueueEvent restored = roundTrip(original);

        assertThat(restored.admitToken()).isEqualTo("adm_9f3c");
        assertThat(restored.admittedAt()).isEqualTo(admittedAt);
        assertThat(restored).isEqualTo(original);
    }

    /**
     * ENQUEUED에는 두 칸이 없다. null이 빠지거나 문자열 "null"이 되면 컨슈머가
     * admit_token 칸에 쓰레기를 넣는다.
     */
    @Test
    @DisplayName("ENQUEUED의 admitToken·admittedAt은 왕복 후에도 null이다")
    void keepsAdmitFieldsNullForEnqueued() {
        EnqueueEvent restored = roundTrip(new EnqueueEvent(
                "ENQUEUED", "tok_a1b2", "q_ticket", 42L, "user-1", 1234L,
                Instant.parse("2026-08-10T12:34:56.789Z"), null, null, null));

        assertThat(restored.admitToken()).isNull();
        assertThat(restored.admittedAt()).isNull();
    }
}
