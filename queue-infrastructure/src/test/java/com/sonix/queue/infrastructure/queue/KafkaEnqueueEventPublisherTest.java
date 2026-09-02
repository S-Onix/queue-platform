package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 발행 어댑터의 계약 두 가지를 고정한다 — <b>파티션 키</b>와 <b>실패의 전달</b>.
 *
 * <p>브로커를 띄우지 않는다. 두 계약 모두 {@code KafkaTemplate} 호출 인자와 예외 변환의
 * 문제라, 실 브로커는 아무것도 더 말해주지 않으면서 태그와 CI 서비스만 요구한다.
 * 실제로 브로커가 있어야만 검증되는 것(직렬화 계약)은
 * {@link KafkaSerdeContractTest}가 따로 맡는다.
 */
class KafkaEnqueueEventPublisherTest {

    private static final String TOPIC = "token-lifecycle";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

    private KafkaEnqueueEventPublisher publisher(long timeoutMs) {
        return new KafkaEnqueueEventPublisher(template, TOPIC, timeoutMs);
    }

    private EnqueueEvent event() {
        return new EnqueueEvent("ENQUEUED", "tok_partition_key", "q_test", 7L,
                "user-1", 42L, Instant.parse("2026-09-02T00:00:00.123Z"), null, null, null);
    }

    @Test
    @DisplayName("🔴 파티션 키는 tokenId다 — 이 한 줄이 상태 전이 순서 보장의 전제다")
    void partitionKeyIsTokenId() {
        when(template.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        EnqueueEvent e = event();

        publisher(3000).publish(e);

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(template).send(topic.capture(), key.capture(), value.capture());

        assertThat(topic.getValue()).isEqualTo(TOPIC);
        // queueId로 바뀌면 한 큐 30만이 한 파티션에 몰리고, 지워지면 라운드로빈이라
        // 같은 토큰의 WAITING→ADMIT_ISSUED가 다른 파티션에 흩어져 순서가 뒤집힌다.
        assertThat(key.getValue()).isEqualTo(e.tokenId());
        assertThat(value.getValue()).isSameAs(e);
    }

    @Test
    @DisplayName("발행이 실패하면 503으로 올라간다 — 삼키면 Redis엔 있고 DB엔 없는 유령이 남는다")
    void publishFailureBecomes503() {
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(template.send(anyString(), anyString(), any())).thenReturn((CompletableFuture) failed);

        assertThatThrownBy(() -> publisher(3000).publish(event()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
    }

    @Test
    @DisplayName("타임아웃도 503이고, 재시도하지 않는다 — '실패'가 아니라 '모름'이라 중복만 는다")
    void timeoutBecomes503WithoutRetry() {
        // 영영 완료되지 않는 future → get(timeout)이 TimeoutException
        when(template.send(anyString(), anyString(), any()))
                .thenReturn((CompletableFuture) new CompletableFuture<Object>());

        assertThatThrownBy(() -> publisher(50).publish(event()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);

        // 재시도가 들어오면 이 단정이 깨진다.
        verify(template).send(anyString(), anyString(), any());
    }
}
