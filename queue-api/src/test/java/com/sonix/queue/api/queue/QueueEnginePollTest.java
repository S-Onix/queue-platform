package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueSnapshot;
import com.sonix.queue.domain.queue.TokenRepository;
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
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueueEngineService.poll() 단위 테스트 (Mockito, Spring/Redis 없음).
 *
 * <p>Redis 어댑터(검증+keepalive)·스냅샷캐시·시계를 모두 목킹하여 poll의 조립 로직만 검증:
 * 검증 실패 404 분기, keepalive ka 위임, nextPollAfterSec 등급, PollResult 조립.
 * tokenId 대조 자체는 Lua 안에서 일어나므로 PollRedisAdapterIntegrationTest가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueEnginePollTest {

    @Mock private QueueRepository queueRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;
    @Mock private QueueSnapshotCache snapshotCache;

    private static final long NOW = 1_700_000_000_000L;
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private QueueEngineService service;

    @BeforeEach
    void setUp() {
        service = new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher, snapshotCache, clock);
    }

    @Test
    @DisplayName("waiting에도 admit-by-token에도 없으면 TOKEN_NOT_FOUND, 스냅샷 미조회")
    void notWaiting_andNotAdmitted_throws() {
        when(queueEngine.verifyWaiting("q1", 5000L, "tok_x", true, NOW)).thenReturn(false);
        when(queueEngine.findAdmitTokenByTokenId("q1", "tok_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("q1", "tok_x", 5000L, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_NOT_FOUND));

        verify(snapshotCache, never()).get(anyString());
    }

    @Test
    @DisplayName("admit되면 waiting에서 빠지지만 404가 아니라 ready=true + admitToken이다 (U8 회귀 방지)")
    void admitted_returnsReadyWithAdmitToken() {
        // admit.lua의 ZPOPMIN이 waiting에서 빼갔으므로 검증은 실패한다. 여기서 404를 주면
        // 정상 입장자가 종료 신호를 받는다 — admit 도입 전에는 없던 회귀다.
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(false);
        when(queueEngine.findAdmitTokenByTokenId("q1", "tok_x")).thenReturn(Optional.of("adm_1"));
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(200L, 5_000L));

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isTrue();
        assertThat(r.admitToken()).isEqualTo("adm_1");
    }

    @Test
    @DisplayName("TTL 만료로 WAITING 복귀한 사람은 검증을 통과한다 → 정상 대기 응답(404도 ready도 아님)")
    void returnedAfterAdmitExpiry_pollsNormally() {
        // 복귀 배치가 원래 seq 그대로 되돌려 놨으므로 admit-by-token은 이미 만료돼 없다.
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(1L, 10_000L));

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isFalse();
        assertThat(r.admitToken()).isNull();
        // 핫패스(최대 15만/s)에 Redis 왕복이 늘지 않는다 — 대기 중이면 두 번째 조회는 없다.
        verify(queueEngine, never()).findAdmitTokenByTokenId(anyString(), anyString());
    }

    @Test
    @DisplayName("ka=false면 keepalive=false로 위임, frontSeq/total은 스냅샷값·ready=false")
    void waiting_noKeepalive() {
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(1L, 10_000L));

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isFalse();
        assertThat(r.admitToken()).isNull();
        assertThat(r.frontSeq()).isEqualTo(1L);
        assertThat(r.total()).isEqualTo(10_000L);
        verify(queueEngine).verifyWaiting("q1", 100L, "tok_x", false, NOW);
    }

    @Test
    @DisplayName("ka=true면 tokenId·now(clock)와 함께 keepalive=true로 위임")
    void waiting_keepalive() {
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", true, NOW)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(1L, 10_000L));

        service.poll("q1", "tok_x", 100L, true);

        verify(queueEngine).verifyWaiting("q1", 100L, "tok_x", true, NOW);
    }

    @DisplayName("nextPollAfterSec: rank 구간별 등급 + 지터는 등급 하한 위로만 (base ~ base+max(1,base/4))")
    @ParameterizedTest(name = "seq={0}, frontSeq={1} → {2}~{3}s")
    @CsvSource({
            "10,    1,  2,  3",     // rank 9     → ≤50    → 2  (+0~1)
            "600,   1,  5,  6",     // rank 599   → ≤1000  → 5  (+0~1)
            "3001,  1,  10, 12",    // rank 3000  → ≤5000  → 10 (+0~2)
            "8001,  1,  15, 18",    // rank 8000  → ≤10000 → 15 (+0~3)
            "20001, 1,  20, 25"     // rank 20000 → else   → 20 (+0~5)
    })
    void nextPollAfterSec_tiers(long seq, long frontSeq, int min, int max) {
        when(queueEngine.verifyWaiting("q1", seq, "tok_x", false, NOW)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(frontSeq, 100_000L));

        // 지터가 있으므로 단발 호출로는 하한 위반을 못 잡는다. 반복해서 구간 전체를 본다.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            seen.add(service.poll("q1", "tok_x", seq, false).nextPollAfterSec());
        }

        assertThat(seen).allSatisfy(v -> assertThat(v).isBetween(min, max));
        assertThat(seen).hasSizeGreaterThan(1);   // 지터가 실제로 흩어지는가 (전부 같은 값이면 몰림 그대로)

        // 구간의 양 끝이 실제로 나오는가. 상한을 안 보면 nextInt의 +1이 빠져 상한이 영영
        // 안 나와도(폭이 1 좁아져도) 위 두 assert는 그대로 통과한다.
        // 300회면 폭이 가장 넓은 20s 등급(6종)에서도 한쪽 끝을 못 볼 확률이 (5/6)^300 ≈ 1e-24.
        assertThat(seen).contains(min, max);
    }

    @Test
    @DisplayName("frontSeq=-1(빈 스냅샷) 엣지 → rank 0으로 처리 → 2~3s")
    void emptySnapshotEdge() {
        when(queueEngine.verifyWaiting("q1", 5000L, "tok_x", false, NOW)).thenReturn(true);
        when(snapshotCache.get("q1")).thenReturn(new QueueSnapshot(-1L, 0L));

        PollResult r = service.poll("q1", "tok_x", 5000L, false);

        assertThat(r.nextPollAfterSec()).isBetween(2, 3);   // rank=0 → ≤50 → 2 (+지터)
        assertThat(r.frontSeq()).isEqualTo(-1L);
    }
}
