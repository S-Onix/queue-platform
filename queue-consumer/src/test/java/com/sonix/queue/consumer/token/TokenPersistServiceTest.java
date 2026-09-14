package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenRepository;
import com.sonix.queue.domain.queue.TokenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <b>{@code persistAll}이 구간을 받은 순서 그대로, 하나도 빠뜨리지 않고 적재하는가.</b>
 *
 * <p>🔴 <b>이 클래스가 없는 동안 결함 두 개가 전 스위트 초록으로 통과했다</b>(2026-09-14 실측):
 * 구간 순서를 뒤집어도, 마지막 구간을 빠뜨려도 480여 건이 전부 통과했다. 원인은 하나다 —
 * {@link TokenLifecycleConsumerTest}가 {@code TokenPersistService}를 <b>목으로</b> 두므로
 * 이 클래스의 본문은 어떤 테스트에서도 실행되지 않았다.
 *
 * <p>하필 구간을 한 트랜잭션으로 합치면서 <b>순서 보존 책임의 절반이 컨슈머에서 이쪽으로 넘어왔다</b>.
 * 컨슈머 쪽 절반(구간 목록을 도착 순으로 만든다)은 {@code TokenLifecycleConsumerTest}가 지키고,
 * 넘어온 절반(그 목록을 순서대로 돈다)을 여기서 지킨다.
 *
 * <p>순서가 왜 중요한지는 {@code TokenJpaAdapter.saveAllIfAbsent} 주석에 있다 — 뒤집히면
 * {@code completed_at}·{@code admitted_at}이 NULL로 굳어 complete가 영구 404가 되고 과금이 누락된다.
 * 실물 DB로 그걸 확인하는 가드는 {@code TokenJpaAdapterIntegrationTest}에 따로 있다(@Tag("mysql")).
 * 여기는 Spring 없이 호출 순서만 본다.
 */
class TokenPersistServiceTest {

    private final TokenRepository repository = mock(TokenRepository.class);
    private final TokenPersistService service = new TokenPersistService(repository);

    /**
     * 🔴 구간 순서를 뒤집으면 빨개져야 한다.
     *
     * <p><b>도착 순서를 일부러 전이 순서와 어긋나게 둔다</b>(ENQUEUED → COMPLETED → ADMITTED).
     * 전이 순서대로 넣으면 "정렬했는지 순서를 보존했는지" 구분할 수 없다 —
     * {@code TokenLifecycleConsumerTest}가 같은 이유로 쓰는 수법이다.
     */
    @Test
    @DisplayName("🔴 persistAll은 구간을 받은 순서 그대로 적재한다")
    void persistAll_appliesSegmentsInGivenOrder() {
        List<TokenPersistService.Segment> segments = List.of(
                segment(TokenEventType.ENQUEUED, TokenStatus.WAITING, 0),
                segment(TokenEventType.COMPLETED, TokenStatus.COMPLETED, 1),
                segment(TokenEventType.ADMITTED, TokenStatus.ADMIT_ISSUED, 2));

        service.persistAll(segments);

        InOrder order = inOrder(repository);
        order.verify(repository).saveAllIfAbsent(anyList());
        order.verify(repository).applyTransition(eq(TokenEventType.COMPLETED), anyList());
        order.verify(repository).applyTransition(eq(TokenEventType.ADMITTED), anyList());
        order.verifyNoMoreInteractions();
    }

    /**
     * 🔴 구간을 하나라도 빠뜨리면 빨개져야 한다.
     *
     * <p>순서 단정만으로는 안 잡힌다 — 마지막 구간을 버려도 남은 것들의 순서는 여전히 옳다.
     * 그래서 <b>총 호출 수</b>를 따로 못박는다.
     */
    @Test
    @DisplayName("🔴 persistAll은 구간을 하나도 빠뜨리지 않는다")
    void persistAll_appliesEverySegment() {
        List<TokenPersistService.Segment> segments = List.of(
                segment(TokenEventType.ENQUEUED, TokenStatus.WAITING, 0),
                segment(TokenEventType.ADMITTED, TokenStatus.ADMIT_ISSUED, 1),
                segment(TokenEventType.COMPLETED, TokenStatus.COMPLETED, 2),
                segment(TokenEventType.EXPIRED, TokenStatus.EXPIRED, 3));

        service.persistAll(segments);

        verify(repository, times(1)).saveAllIfAbsent(anyList());
        verify(repository, times(3)).applyTransition(org.mockito.ArgumentMatchers.any(), anyList());
    }

    /**
     * {@code ENQUEUED}만 다른 포트 메서드로 간다 — 신규 적재는 가드가 필요 없고(충돌 시 no-op),
     * 나머지는 허용 출발 상태를 강제하는 가드가 이벤트마다 다르기 때문이다(§80 가드 표).
     * 이 분기가 뒤집히면 신규 토큰이 전이 SQL을 타 <b>가드에 막혀 조용히 사라진다</b>.
     */
    @Test
    @DisplayName("ENQUEUED만 saveAllIfAbsent로, 나머지는 applyTransition으로 간다")
    void enqueuedGoesToSaveAllIfAbsent() {
        service.persist(TokenEventType.ENQUEUED, List.of(token(TokenStatus.WAITING, 0)));
        service.persist(TokenEventType.EXPIRED, List.of(token(TokenStatus.EXPIRED, 1)));

        verify(repository).saveAllIfAbsent(anyList());
        verify(repository).applyTransition(eq(TokenEventType.EXPIRED), anyList());
        verify(repository, org.mockito.Mockito.never())
                .applyTransition(eq(TokenEventType.ENQUEUED), anyList());
    }

    /** 빈 목록이면 포트를 한 번도 건드리지 않는다 — 빈 트랜잭션조차 의미가 없다. */
    @Test
    @DisplayName("빈 구간 목록은 아무것도 적재하지 않는다")
    void emptySegments_doNothing() {
        service.persistAll(List.of());

        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    // ---------------------------------------------------------------------

    private static TokenPersistService.Segment segment(TokenEventType type, TokenStatus status, int offset) {
        return new TokenPersistService.Segment(type, List.of(token(status, offset)), offset);
    }

    private static Token token(TokenStatus status, long seq) {
        return Token.transition(status, "tok_" + seq, "q_test", 1L, "u" + seq, seq,
                LocalDateTime.of(2026, 9, 14, 10, 0), null, null);
    }
}
