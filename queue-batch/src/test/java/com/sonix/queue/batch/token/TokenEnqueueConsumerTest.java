package com.sonix.queue.batch.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TokenEnqueueConsumer 단위 테스트 (인프라 없음, Mockito).
 *
 * <p>Consumer의 <b>계약</b>만 검증한다 — DB/Kafka는 각각 어댑터 통합 테스트/발행 통합 테스트가 맡는다:
 * <ul>
 *   <li>이벤트 → 도메인 토큰 매핑 (특히 {@code Instant → LocalDateTime(UTC)} 결정론적 변환)</li>
 *   <li>순서 계약: DB 저장(append) <b>성공 후</b> ack — 즉 append → acknowledge 순서</li>
 *   <li>실패 시 ack 하지 않음: append가 던지면 예외 전파 + ack 미호출 → 재전달/멱등에 위임</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TokenEnqueueConsumerTest {

    @Mock private TokenEnqueueService tokenEnqueueService;
    @Mock private Acknowledgment ack;

    @InjectMocks private TokenEnqueueConsumer consumer;

    @Captor private ArgumentCaptor<List<Token>> tokensCaptor;

    @Test
    @DisplayName("정상 소비: 이벤트를 WAITING 토큰으로 매핑(Instant→UTC LocalDateTime)하고 append 후 ack 한다")
    void consume_mapsThenAcks() {
        Instant issuedAt = Instant.parse("2026-07-23T01:23:45.678Z");
        EnqueueEvent event = new EnqueueEvent("tok_1", "q_1", 42L, "user_1", 100L, issuedAt);

        consumer.consume(List.of(event), ack);

        // 매핑 검증
        verify(tokenEnqueueService).append(tokensCaptor.capture());
        List<Token> mapped = tokensCaptor.getValue();
        assertThat(mapped).hasSize(1);
        Token t = mapped.get(0);
        assertThat(t.getTokenId()).isEqualTo("tok_1");
        assertThat(t.getQueueId()).isEqualTo("q_1");
        assertThat(t.getTenantId()).isEqualTo(42L);
        assertThat(t.getUserId()).isEqualTo("user_1");
        assertThat(t.getSeq()).isEqualTo(100L);
        assertThat(t.getStatus()).isEqualTo(TokenStatus.WAITING);
        // Instant(UTC) → LocalDateTime(UTC): 벽시계 숫자가 01:23:45.678 그대로 (millis 보존)
        assertThat(t.getIssuedAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 23, 1, 23, 45, 678_000_000));

        // 순서 계약: append(=커밋) 먼저, 그다음 ack
        InOrder order = inOrder(tokenEnqueueService, ack);
        order.verify(tokenEnqueueService).append(anyList());
        order.verify(ack).acknowledge();
    }

    @Test
    @DisplayName("append가 실패하면 ack하지 않고 예외를 전파한다 (재전달 → 멱등에 위임)")
    void consume_doesNotAck_whenAppendFails() {
        EnqueueEvent event = new EnqueueEvent("tok_x", "q_1", 1L, "u", 1L,
                Instant.parse("2026-07-23T00:00:00Z"));
        doThrow(new RuntimeException("DB down")).when(tokenEnqueueService).append(anyList());

        assertThatThrownBy(() -> consumer.consume(List.of(event), ack))
                .isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("배치로 받은 여러 이벤트를 모두 매핑해 한 번에 append 한다")
    void consume_mapsWholeBatch() {
        List<EnqueueEvent> events = List.of(
                new EnqueueEvent("tok_a", "q_1", 1L, "ua", 1L, Instant.parse("2026-07-23T00:00:00Z")),
                new EnqueueEvent("tok_b", "q_1", 1L, "ub", 2L, Instant.parse("2026-07-23T00:00:01Z")),
                new EnqueueEvent("tok_c", "q_1", 1L, "uc", 3L, Instant.parse("2026-07-23T00:00:02Z"))
        );

        consumer.consume(events, ack);

        verify(tokenEnqueueService).append(tokensCaptor.capture());
        assertThat(tokensCaptor.getValue())
                .extracting(Token::getTokenId)
                .containsExactly("tok_a", "tok_b", "tok_c");
        verify(ack).acknowledge();
    }
}
