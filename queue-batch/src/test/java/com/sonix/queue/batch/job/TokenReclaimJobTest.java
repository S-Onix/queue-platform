package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.ReclaimedToken;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.domain.queue.TokenEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TokenReclaimJob} 단위 테스트.
 *
 * <p>회수 자체(ZREM/HDEL)는 Lua가 하고 {@code AdmitExpiryReclaimTest}(§36) ·
 * {@code InactiveReclaimTest}(§82)가 실제 Redis로 검증한다.
 * 여기서 보는 것은 잡의 책임인 <b>{@code EXPIRED} 발행 계약</b>과 <b>실패 격리</b>다.
 */
@ExtendWith(MockitoExtension.class)
class TokenReclaimJobTest {

    private static final Instant ISSUED_AT = Instant.ofEpochMilli(1_700_000_000_000L);

    @Mock private QueueRepository queueRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;

    // 목이 아니라 진짜 레지스트리다 — gauge 등록·값 반영이 이 테스트가 보려는 것이다.
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TokenReclaimJob job;

    @BeforeEach
    void setUp() {
        // 생성자에서 gauge를 등록하므로 @InjectMocks 대신 직접 만든다.
        job = new TokenReclaimJob(queueRepository, queueEngine, eventPublisher, meterRegistry);
    }

    private static Queue queue(String queueId, long tenantId) {
        return Queue.reconstruct(1L, queueId, tenantId, "테스트큐", 100_000, 7200, 300,
                QueueStatus.ACTIVE, LocalDateTime.now(), null);
    }

    /**
     * 🔴 §82. {@code inactiveTtl}은 <b>큐 설정</b>이므로 cutoff를 Java가 계산해 넘겨야 한다 —
     * Lua는 큐마다 다른 그 값을 알 수 없다. 상수를 박거나 {@code now}를 그대로 넘기면
     * <b>대기자 전원이 즉시 회수된다.</b>
     */
    @Test
    @DisplayName("inactive 회수의 cutoff = now - inactiveTtl*1000 (큐 설정을 쓴다) (§82)")
    void inactiveCutoffUsesQueueInactiveTtl() {
        // inactiveTtl = 300초인 큐
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimInactive(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-idle", 5L, "tok_5", ISSUED_AT)));

        long before = System.currentTimeMillis();
        job.reclaim();
        long after = System.currentTimeMillis();

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(queueEngine).claimInactive(eq("q_dev_a"), cutoff.capture(), anyInt());
        assertThat(cutoff.getValue())
                .as("300초 전이어야 한다 — now를 그대로 넘기면 대기자 전원이 회수된다")
                .isBetween(before - 300_000L, after - 300_000L);

        // 회수분도 EXPIRED로 발행된다 (admit 만료와 같은 이벤트)
        ArgumentCaptor<EnqueueEvent> ev = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher).publish(ev.capture());
        assertThat(ev.getValue().eventType()).isEqualTo(TokenEventType.EXPIRED.name());
        assertThat(ev.getValue().userId()).isEqualTo("user-idle");
    }

    @Test
    @DisplayName("회수한 토큰마다 EXPIRED를 발행한다 — admitToken·admittedAt은 둘 다 null (§36)")
    void publishesExpiredPerReclaimedToken() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-1", 7L, "tok_1", ISSUED_AT)));

        job.reclaim();

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
                .thenReturn(List.of(new ReclaimedToken("ghost", 5L, null, null)));

        job.reclaim();

        verify(eventPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("한 큐의 Redis 장애가 다음 큐의 회수를 막지 않는다")
    void oneQueueFailureDoesNotStopTheSweep() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_broken", 1L), queue("q_dev_ok", 2L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_broken"), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_ok"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-2", 9L, "tok_2", ISSUED_AT)));

        job.reclaim();

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_2".equals(e.tokenId())));
    }

    /**
     * 🔴 <b>같은 큐에서 admit 경로가 죽어도 이탈 회수는 돌아야 한다.</b>
     *
     * <p>루프 본문의 두 {@code EVAL}을 하나의 {@code try/catch}로 묶는 리팩터는 아주 자연스럽다.
     * 그러면 admit-expire가 실패하는 큐는 <b>이탈 회수가 통째로 멈춘다</b> — §82가 남긴 유일한
     * 경로가 조용히 죽는데 다른 테스트는 전부 초록이다. 이 단언이 그걸 잡는다.
     */
    @Test
    @DisplayName("같은 큐에서 admit 경로가 실패해도 inactive 회수는 계속된다 (§82)")
    void admitPathFailureDoesNotBlockInactiveReclaim() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("admit claim 실패"));
        when(queueEngine.claimInactive(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-idle", 5L, "tok_5", ISSUED_AT)));

        job.reclaim();

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_5".equals(e.tokenId())));
    }

    /** 반대 방향도 같다 — inactive가 죽어도 다음 큐의 회수를 막지 않는다. */
    @Test
    @DisplayName("한 큐의 inactive 회수 실패가 다음 큐를 막지 않는다 (§82)")
    void inactiveFailureDoesNotStopOtherQueues() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_broken", 1L), queue("q_dev_ok", 2L)));
        when(queueEngine.claimInactive(eq("q_dev_broken"), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));
        when(queueEngine.claimInactive(eq("q_dev_ok"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-9", 9L, "tok_9", ISSUED_AT)));

        job.reclaim();

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_9".equals(e.tokenId())));
    }

    @Test
    @DisplayName("발행 실패는 삼킨다 — 회수는 Redis에서 이미 확정됐고 되돌릴 수단이 없다")
    void publishFailureIsSwallowed() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_a", 1L), queue("q_dev_b", 2L)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-1", 1L, "tok_1", ISSUED_AT)));
        when(queueEngine.claimExpiredAdmits(eq("q_dev_b"), anyLong(), anyInt()))
                .thenReturn(List.of(new ReclaimedToken("user-2", 2L, "tok_2", ISSUED_AT)));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                        e -> "tok_1".equals(e.tokenId())));

        job.reclaim();   // 예외가 새어 나오면 다음 주기까지 잡이 죽는다

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.argThat(
                e -> "tok_2".equals(e.tokenId())));
    }

    /**
     * U9 좀비 탐지. gauge는 <b>큐별 합</b>이고 판정 여유값은 {@link TokenReclaimJob#ORPHAN_LAG}다.
     *
     * <p>이 테스트가 지키는 것 — 잡이 관측을 실제로 호출하고 <b>그 반환값을 쓰는가</b>.
     * 호출을 빠뜨리면 gauge가 영원히 0이라 <b>초록인 채로 탐지 수단이 없다</b>. 스텁 값을 3/2로
     * 서로 다르게 둔 것은 의도다 — 합(5)·마지막(2)·최댓값(3)이 전부 구분된다.
     *
     * <p>⚠️ 판정 로직 자체는 {@code RedisQueueEngine}에 있고 여기선 목이다.
     * 판정 자체는 {@code OrphanWaitingCountTest}(실 Redis)가 잡는다.
     */
    @Test
    @DisplayName("좀비 gauge = 큐별 countOrphanedWaiting의 합 (U9)")
    void orphanGaugeSumsPerQueue() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_a", 42L), queue("q_dev_b", 42L)));
        when(queueEngine.countOrphanedWaiting("q_dev_a")).thenReturn(3L);
        when(queueEngine.countOrphanedWaiting("q_dev_b")).thenReturn(2L);

        job.reclaim();

        assertThat(meterRegistry.get("queue.waiting.orphans").gauge().value()).isEqualTo(5.0);
    }

    /**
     * 관측은 <b>부가 기능</b>이다. 한 큐의 Redis 장애로 {@code reclaim()} 전체가 죽으면 나머지 큐의
     * 관측까지 함께 멈춘다.
     *
     * <p>⚠️ 이 테스트가 못박는 계약은 딱 그것뿐이다 — <b>실패한 큐는 0으로 집계된다</b>(7.0이지
     * 그 이상이 아니다). 즉 클러스터 장애 중 총합은 조용히 내려가 더 건강해 보인다.
     * 그 한계는 의도된 것이고 {@code countOrphans} Javadoc에 적혀 있다.
     */
    @Test
    @DisplayName("한 큐의 관측 실패가 나머지 큐의 좀비 집계를 막지 않는다 (U9)")
    void orphanCountFailureDoesNotStopOtherQueues() {
        when(queueRepository.findAll())
                .thenReturn(List.of(queue("q_dev_a", 42L), queue("q_dev_b", 42L)));
        when(queueEngine.countOrphanedWaiting("q_dev_a"))
                .thenThrow(new RuntimeException("cluster1 down"));
        when(queueEngine.countOrphanedWaiting("q_dev_b")).thenReturn(7L);

        job.reclaim();

        assertThat(meterRegistry.get("queue.waiting.orphans").gauge().value()).isEqualTo(7.0);
    }

    /**
     * 🔴 §82 구멍 ③의 마지노선이 실제로 호출되는가. cutoff는 <b>큐 설정</b>({@code waitingTtl})으로
     * 계산해야 한다 — 상수를 박거나 {@code now}를 그대로 넘기면 <b>대기자 전원이 즉시 회수된다.</b>
     * {@code inactiveTtl}(300)이 아니라 {@code waitingTtl}(7200)을 쓰는지도 함께 못박는다.
     * 둘을 바꿔 쓰면 절대 만료가 24배 빨라져 정상 대기자가 잘려나간다.
     */
    @Test
    @DisplayName("waitingTtl 회수의 cutoff = now - waitingTtl*1000 (inactiveTtl이 아니다)")
    void waitingCutoffUsesQueueWaitingTtl() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));

        long before = System.currentTimeMillis();
        job.reclaim();
        long after = System.currentTimeMillis();

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(queueEngine).claimExpiredWaiting(eq("q_dev_a"), cutoff.capture(), anyInt());

        // queue()가 waitingTtl=7200, inactiveTtl=300으로 만든다.
        assertThat(cutoff.getValue())
                .isBetween(before - 7200_000L, after - 7200_000L);
    }

    /**
     * 세 경로는 서로를 막지 않는다. 한 경로의 Redis 장애가 나머지를 멈추면, 정작 장애 때
     * 회수가 통째로 멈춰 큐가 부풀어 오른다.
     */
    @Test
    @DisplayName("waitingTtl 경로 실패가 admit·inactive 회수를 막지 않는다")
    void waitingFailureDoesNotBlockOtherPaths() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_dev_a", 42L)));
        when(queueEngine.claimExpiredWaiting(anyString(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("cluster down"));

        job.reclaim();

        verify(queueEngine).claimExpiredAdmits(eq("q_dev_a"), anyLong(), anyInt());
        verify(queueEngine).claimInactive(eq("q_dev_a"), anyLong(), anyInt());
    }
}
