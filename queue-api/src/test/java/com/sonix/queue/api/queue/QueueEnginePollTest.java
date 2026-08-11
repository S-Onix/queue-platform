package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueueEngineService.poll() 단위 테스트 (Mockito, Spring/Redis 없음).
 *
 * <p>Redis 어댑터(존재판정)·스냅샷캐시·시계를 모두 목킹하여 poll의 조립 로직만 검증:
 * 존재판정 404 분기, keepalive ka 분기, nextPollAfterSec 등급, PollResult 조립.
 */
@ExtendWith(MockitoExtension.class)
class QueueEnginePollTest {

    @Mock private QueueRepository queueRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;
    @Mock private QueueSnapshotCache snapshotCache;

    private static final long NOW = 1_700_000_000_000L;
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private QueueEngineService service;

    @BeforeEach
    void setUp() {
        service = new QueueEngineService(queueRepository, queueEngine, eventPublisher, snapshotCache, clock);
    }

    @Test
    @DisplayName("대기 항목이 없으면 TOKEN_NOT_FOUND, 스냅샷·keepalive 미실행")
    void notWaiting_throws() {
        when(queueEngine.isWaiting("q1", 5000L)).thenReturn(false);

        assertThatThrownBy(() -> service.poll("q1", "tok_x", 5000L, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_NOT_FOUND));

        verify(snapshotCache, never()).get(anyString());
        verify(queueEngine, never()).touchLastActive(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("ka=false면 keepalive 미호출, frontSeq/total은 스냅샷값·ready=false")
    void waiting_noKeepalive() {
        when(queueEngine.isWaiting("q1", 100L)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(1L, 10_000L));

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isFalse();
        assertThat(r.admitToken()).isNull();
        assertThat(r.frontSeq()).isEqualTo(1L);
        assertThat(r.total()).isEqualTo(10_000L);
        verify(queueEngine, never()).touchLastActive(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("ka=true & 존재 시 keepalive를 now(clock)로 호출")
    void waiting_keepalive() {
        when(queueEngine.isWaiting("q1", 100L)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(1L, 10_000L));

        service.poll("q1", "tok_x", 100L, true);

        verify(queueEngine).touchLastActive("q1", 100L, NOW);
    }

    @DisplayName("nextPollAfterSec: rank 구간별 등급")
    @ParameterizedTest(name = "seq={0}, frontSeq={1} → {2}s")
    @CsvSource({
            "10,    1,  2",     // rank 9     → ≤50    → 2
            "600,   1,  5",     // rank 599   → ≤1000  → 5
            "3001,  1,  10",    // rank 3000  → ≤5000  → 10
            "8001,  1,  15",    // rank 8000  → ≤10000 → 15
            "20001, 1,  20"     // rank 20000 → else   → 20
    })
    void nextPollAfterSec_tiers(long seq, long frontSeq, int expectedNext) {
        when(queueEngine.isWaiting("q1", seq)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(frontSeq, 100_000L));

        PollResult r = service.poll("q1", "tok_x", seq, false);

        assertThat(r.nextPollAfterSec()).isEqualTo(expectedNext);
    }

    @Test
    @DisplayName("frontSeq=-1(빈 스냅샷) 엣지 → rank 0으로 처리 → 2s")
    void emptySnapshotEdge() {
        when(queueEngine.isWaiting("q1", 5000L)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(-1L, 0L));

        PollResult r = service.poll("q1", "tok_x", 5000L, false);

        assertThat(r.nextPollAfterSec()).isEqualTo(2);   // rank=0 → ≤50 → 2
        assertThat(r.frontSeq()).isEqualTo(-1L);
    }
}
