package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReconcileJob} 단위 테스트.
 *
 * <p>대사 판정 자체(SQL·ZCOUNT)는 어댑터에 있고 실 DB/Redis 테스트가 본다.
 * 여기서 보는 것은 잡의 책임인 <b>부호 해석</b>과 <b>기준 시각 계산</b>이다 — 둘 다 틀려도
 * 조용히 통과하는 종류다.
 */
@ExtendWith(MockitoExtension.class)
class ReconcileJobTest {

    @Mock private QueueRepository queueRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private TokenRepository tokenRepository;

    // 목이 아니라 진짜 레지스트리다 — 게이지 등록·값 반영이 이 테스트가 보려는 것이다.
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new ReconcileJob(queueRepository, queueEngine, tokenRepository, meterRegistry);
    }

    private static Queue queue(String queueId) {
        return Queue.reconstruct(1L, queueId, 42L, "테스트큐", 100_000, 7200, 300,
                QueueStatus.ACTIVE, LocalDateTime.now(), null);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    /**
     * 🔴 <b>부호가 방향이다.</b> 양수(Redis가 많다)는 유령 토큰, 음수(DB가 많다)는 종료 이벤트
     * 유실이고 <b>조치가 정반대</b>다. 두 갈래를 한 게이지에 합치거나 부호를 뒤집으면
     * 나중에 붙일 복구가 <b>정확히 반대편을 고친다.</b>
     */
    @Test
    @DisplayName("갭의 부호로 유령(Redis>DB)과 낡음(DB>Redis)을 갈라 센다")
    void splitsGapBySign() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_ghost"), queue("q_stale")));
        when(tokenRepository.findSettledMaxSeq(anyString(), any())).thenReturn(1000L);

        when(queueEngine.countWaitingUpTo("q_ghost", 1000L)).thenReturn(105L);
        when(tokenRepository.countWaitingUpTo("q_ghost", 1000L)).thenReturn(100L);   // +5 유령

        when(queueEngine.countWaitingUpTo("q_stale", 1000L)).thenReturn(90L);
        when(tokenRepository.countWaitingUpTo("q_stale", 1000L)).thenReturn(93L);    // -3 낡음

        job.reconcile();

        assertThat(gauge("queue.reconcile.ghosts")).isEqualTo(5.0);
        assertThat(gauge("queue.reconcile.stale")).isEqualTo(3.0);
    }

    /**
     * 🔴 <b>정착 시간이 없으면 컨슈머 지연이 곧 오탐이다.</b> 방금 들어온 사람은 Kafka를 타는 중이라
     * Redis엔 있고 DB엔 없는 게 정상이다 — 실측에서 밀린 500건이 40초 만에 0으로 회복됐다.
     * 이 값을 {@code now}로 넘기면 유입이 있는 모든 큐가 상시 갭으로 잡힌다.
     */
    @Test
    @DisplayName("대사 기준선 = now - SETTLE_SECONDS (UTC)")
    void settleWindowIsApplied() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_a")));
        when(tokenRepository.findSettledMaxSeq(anyString(), any())).thenReturn(0L);

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        job.reconcile();
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

        ArgumentCaptor<LocalDateTime> cut = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokenRepository).findSettledMaxSeq(eq("q_a"), cut.capture());
        assertThat(cut.getValue())
                .isBetween(before.minusSeconds(ReconcileJob.SETTLE_SECONDS),
                           after.minusSeconds(ReconcileJob.SETTLE_SECONDS));
    }

    /**
     * 🔴 <b>기준은 admitToken TTL(60초)이 아니라 complete 유효 창(300초)이다.</b>
     * 더 일찍 자르면 <b>정상적인 늦은 통보가 404를 받는다</b> — 실측으로 확인된 경로다
     * (admit 후 98초에도 complete가 200을 돌려준다).
     */
    @Test
    @DisplayName("ADMIT_ISSUED 정리 기준 = now - COMPLETE_VALID_WINDOW_SECONDS (admitTtl이 아니다)")
    void staleAdmittedCutoffUsesCompleteWindow() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_a")));
        when(tokenRepository.findSettledMaxSeq(anyString(), any())).thenReturn(0L);

        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        job.reconcile();
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

        ArgumentCaptor<LocalDateTime> cut = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokenRepository).expireStaleAdmitted(eq("q_a"), cut.capture(), eq(ReconcileJob.EXPIRE_LIMIT));
        assertThat(cut.getValue())
                .isBetween(before.minusSeconds(Token.COMPLETE_VALID_WINDOW_SECONDS),
                           after.minusSeconds(Token.COMPLETE_VALID_WINDOW_SECONDS));
    }

    /** 정착 구간에 토큰이 없는 큐(새 큐)는 대사 대상이 아니다 — Redis를 부르지도 않는다. */
    @Test
    @DisplayName("정착 구간에 토큰이 없으면 Redis를 조회하지 않는다")
    void skipsQueueWithoutSettledTokens() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_new")));
        when(tokenRepository.findSettledMaxSeq(anyString(), any())).thenReturn(0L);

        job.reconcile();

        assertThat(gauge("queue.reconcile.ghosts")).isZero();
        verify(queueEngine, org.mockito.Mockito.never()).countWaitingUpTo(anyString(), anyLong());
    }

    /**
     * 한 큐의 장애가 나머지 큐의 대사와 <b>정리 UPDATE까지</b> 막으면, 정작 장애 때 원장이
     * 통째로 굳는다.
     */
    @Test
    @DisplayName("한 큐의 대사 실패가 나머지 큐와 정리 작업을 막지 않는다")
    void queueFailureDoesNotStopTheRest() {
        when(queueRepository.findAll()).thenReturn(List.of(queue("q_bad"), queue("q_ok")));
        when(tokenRepository.findSettledMaxSeq(eq("q_bad"), any()))
                .thenThrow(new RuntimeException("cluster down"));
        when(tokenRepository.findSettledMaxSeq(eq("q_ok"), any())).thenReturn(500L);
        when(queueEngine.countWaitingUpTo("q_ok", 500L)).thenReturn(7L);
        when(tokenRepository.countWaitingUpTo("q_ok", 500L)).thenReturn(0L);

        job.reconcile();

        assertThat(gauge("queue.reconcile.ghosts")).isEqualTo(7.0);
        verify(tokenRepository).expireStaleAdmitted(eq("q_ok"), any(), anyInt());
    }
}
