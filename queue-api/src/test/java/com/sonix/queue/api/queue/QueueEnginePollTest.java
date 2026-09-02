package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.PacingTier;
import com.sonix.queue.domain.queue.QueueBoard;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code QueueEngineService}의 폴링 두 경로 단위 테스트 (Mockito, Spring/Redis 없음).
 *
 * <p>§79로 <b>엔드포인트가 둘로 갈렸다</b>. 여기서 보는 것도 둘이다:
 * <ul>
 *   <li>{@code status()} — 전원 동일 응답. 큐 실재 판정(404)과 위임뿐이다</li>
 *   <li>{@code poll()} — 개인화 응답. 검증 실패 시의 <b>404 대 ready 분기</b>가 전부다</li>
 * </ul>
 *
 * <p><b>사라진 테스트와 그 이유:</b> {@code nextPollAfterSec_tiers}(등급·지터)와
 * {@code emptySnapshotEdge}({@code frontSeq=-1})는 서버가 더 이상 rank도 간격도 계산하지 않아
 * 검증 대상 자체가 없다. 그 불변식은 SDK로 이관됐고 <b>SDK에는 아직 테스트 인프라가 없다</b> —
 * §79 Consequences가 "비용을 모르고 옮기면 안 된다"고 적은 바로 그 지점이다.
 *
 * <p>tokenId 대조 자체는 Lua 안에서 일어나므로 {@code PollRedisAdapterIntegrationTest}가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueEnginePollTest {

    @Mock private QueueRepository queueRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;

    private static final long NOW = 1_700_000_000_000L;
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    private QueueEngineService service;

    @BeforeEach
    void setUp() {
        service = new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher, clock, 0L);
    }

    // ──────────────────────────────────────────────────────────────────────
    // status() — 큐 전광판
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status: 전광판 값을 그대로 돌려준다. rank도 폴링 간격도 계산하지 않는다")
    void status_returnsBoardAsIs() {
        QueueBoard board = new QueueBoard(47L, PacingTier.DEFAULT);
        when(queueEngine.readStatus("q1")).thenReturn(Optional.of(board));

        assertThat(service.status("q1")).isSameAs(board);
    }

    @Test
    @DisplayName("status: 미지 queueId는 404 QUEUE_NOT_FOUND — DB는 한 줄도 읽지 않는다")
    void status_unknownQueue_throwsWithoutDb() {
        when(queueEngine.readStatus("q_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.status("q_ghost"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.QUEUE_NOT_FOUND));

        // 인증 없는 경로다(§79). 큐 존재 확인을 DB로 하면 임의 문자열만으로 MySQL을 때울 수 있다.
        verify(queueRepository, never()).findByQueueId(anyString());
    }

    // ──────────────────────────────────────────────────────────────────────
    // poll() — 개인 상태
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("waiting에도 admit-by-token에도 없으면 TOKEN_NOT_FOUND")
    void notWaiting_andNotAdmitted_throws() {
        when(queueEngine.verifyWaiting("q1", 5000L, "tok_x", true, NOW)).thenReturn(false);
        when(queueEngine.findAdmitTokenByTokenId("q1", "tok_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("q1", "tok_x", 5000L, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("admit되면 waiting에서 빠지지만 404가 아니라 ready=true + admitToken이다 (U8 회귀 방지)")
    void admitted_returnsReadyWithAdmitToken() {
        // admit.lua의 ZPOPMIN이 waiting에서 빼갔으므로 검증은 실패한다. 여기서 404를 주면
        // 정상 입장자가 종료 신호를 받는다 — admit 도입 전에는 없던 회귀다.
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(false);
        when(queueEngine.findAdmitTokenByTokenId("q1", "tok_x")).thenReturn(Optional.of("adm_1"));

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isTrue();
        assertThat(r.admitToken()).isEqualTo("adm_1");
    }

    @Test
    @DisplayName("아직 admit 안 된 대기자는 검증을 통과한다 → 정상 대기 응답(404도 ready도 아님)")
    void stillWaiting_pollsNormally() {
        // admit-by-token이 없다 = 아직 뽑히지 않았다. (§36으로 "복귀한 사람"이라는 경우는 사라졌다)
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(true);

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isFalse();
        assertThat(r.admitToken()).isNull();
        // 핫패스(최대 15만/s)에 Redis 왕복이 늘지 않는다 — 대기 중이면 두 번째 조회는 없다.
        verify(queueEngine, never()).findAdmitTokenByTokenId(anyString(), anyString());
    }

    @Test
    @DisplayName("대기 중 폴링은 Redis 왕복이 verifyWaiting 1회뿐이다 — 전광판을 다시 읽지 않는다")
    void waiting_doesNotReadBoard() {
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(true);

        PollResult r = service.poll("q1", "tok_x", 100L, false);

        assertThat(r.ready()).isFalse();
        // §79 분할의 핵심. 개인 응답에 공유값(frontSeq/total/간격)을 실으면 EVAL이 다시 늘어난다.
        verify(queueEngine, never()).readStatus(anyString());
    }

    @Test
    @DisplayName("ka=true면 tokenId·now(clock)와 함께 keepalive=true로 위임")
    void waiting_keepalive() {
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", true, NOW)).thenReturn(true);

        service.poll("q1", "tok_x", 100L, true);

        verify(queueEngine).verifyWaiting("q1", 100L, "tok_x", true, NOW);
    }

    @Test
    @DisplayName("ka=false면 keepalive=false로 그대로 위임한다")
    void waiting_noKeepalive() {
        when(queueEngine.verifyWaiting("q1", 100L, "tok_x", false, NOW)).thenReturn(true);

        service.poll("q1", "tok_x", 100L, false);

        verify(queueEngine).verifyWaiting("q1", 100L, "tok_x", false, NOW);
    }
}
