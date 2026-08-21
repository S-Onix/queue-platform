package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.ExpiredAdmit;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.domain.queue.TokenEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdmitTokenExpiryJob} 단위 테스트.
 *
 * <p>회수 자체(ZREM/HDEL)는 Lua가 하고 {@code AdmitExpiryReclaimTest}가 실제 Redis로 검증한다.
 * 여기서 보는 것은 잡의 책임인 <b>{@code EXPIRED} 발행 계약</b>과 <b>실패 격리</b>다.
 */
@ExtendWith(MockitoExtension.class)
class AdmitTokenExpiryJobTest {

    private static final Instant ISSUED_AT = Instant.ofEpochMilli(1_700_000_000_000L);

    @Mock private QueueRepository queueRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;

    @InjectMocks private AdmitTokenExpiryJob job;

    private static Queue queue(String queueId, long tenantId) {
        return Queue.reconstruct(1L, queueId, tenantId, "테스트큐", 100_000, 7200, 300,
                QueueStatus.ACTIVE, LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("회수한 토큰마다 EXPIRED를 발행한다 — admitToken·admittedAt은 둘 다 null (§36)")
    void publishesExpiredPerReclaimedToken() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ExpiredAdmit("user-1", 7L, "tok_1", ISSUED_AT)));

        job.reclaimExpiredAdmits();

        ArgumentCaptor<EnqueueEvent> captor = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher).publish(captor.capture());

        EnqueueEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(TokenEventType.EXPIRED.name());
        assertThat(event.tokenId()).isEqualTo("tok_1");        // = Kafka 파티션 키
        assertThat(event.queueId()).isEqualTo("q_dev_a");
        assertThat(event.tenantId()).isEqualTo(42L);
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.seq()).isEqualTo(7L);                 // 관측용 — 되돌리지 않는다(§36)
        assertThat(event.issuedAt()).isEqualTo(ISSUED_AT);     // 멱등 키의 나머지 절반
        // 옛 admitToken을 실어 보내면 이미 무효가 된 값이 DB에 되살아난다 (§80 null 규약 표)
        assertThat(event.admitToken()).isNull();
        assertThat(event.admittedAt()).isNull();
    }

    @Test
    @DisplayName("tokenId·issuedAt을 모르면 발행하지 않는다 (멱등 키를 추측하면 두 번째 행이 생긴다)")
    void skipsPublishWhenTokenIdUnknown() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ExpiredAdmit("ghost", 5L, null, null)));

        job.reclaimExpiredAdmits();

        verify(eventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("한 큐의 Redis 장애가 다음 큐의 복귀를 막지 않는다")
    void oneQueueFailureDoesNotStopTheSweep() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_broken", 1L), queue("q_dev_ok", 2L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_broken"), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_ok"), anyLong(), anyInt()))
                .thenReturn(List.of(new ExpiredAdmit("user-2", 9L, "tok_2", ISSUED_AT)));

        job.reclaimExpiredAdmits();

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_2".equals(e.tokenId())));
    }

    @Test
    @DisplayName("발행 실패는 삼킨다 — 복귀는 Redis에서 이미 확정됐고 되돌릴 수단이 없다")
    void publishFailureIsSwallowed() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_a", 1L), queue("q_dev_b", 2L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ExpiredAdmit("user-1", 1L, "tok_1", ISSUED_AT)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_b"), anyLong(), anyInt()))
                .thenReturn(List.of(new ExpiredAdmit("user-2", 2L, "tok_2", ISSUED_AT)));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                        e -> "tok_1".equals(e.tokenId())));

        job.reclaimExpiredAdmits();   // 예외가 새어 나오면 다음 주기까지 잡이 죽는다

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_2".equals(e.tokenId())));
    }
}
