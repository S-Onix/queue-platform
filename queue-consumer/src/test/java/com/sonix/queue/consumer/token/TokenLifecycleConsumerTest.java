package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 제약 위반이 났을 때 <b>무엇을 DLT로 보내고 무엇을 보내지 않는가</b>, 그리고
 * <b>타입이 섞인 배치에서 순서가 지켜지는가</b>를 고정한다.
 *
 * <p>이 경로는 로컬에서 재현하기 어렵다. 적재가 멱등({@code ON DUPLICATE KEY})이라
 * 중복으로는 제약 위반이 나지 않고, 길이 초과 같은 실제 위반은 부하 테스트에 섞여 들어오지
 * 않기 때문이다. 그래서 오동작이 <b>운영에서 데이터가 사라진 뒤에야</b> 드러난다 —
 * 테스트로 못박아 두는 이유다.
 */
class TokenLifecycleConsumerTest {

    private final TokenPersistService persistService = mock(TokenPersistService.class);
    private final TokenLifecycleConsumer consumer = new TokenLifecycleConsumer(persistService);

    /**
     * 일시적 원인이었다면 재시도 중 전 건이 적재된다. 이때 예외를 올리면
     * <b>이미 적재를 마친 배치가 통째로 DLT로</b> 간다.
     */
    @Test
    @DisplayName("범인을 특정하지 못하면(재시도 중 전 건 적재) 예외 없이 ack 한다")
    void 범인을_특정하지_못하면_예외_없이_반환한다() {
        // 첫 시도만 실패하고 이후 시도는 성공 = 일시적 원인이 해소된 상황
        doThrow(new DataIntegrityViolationException("일시적"))
                .doNothing()
                .when(persistService).persist(any(), anyList());

        assertThatCode(() -> consumer.consume(events(4)))
                .doesNotThrowAnyException();

        // 최초 1회 + 범인 탐색의 재시도 1회
        verify(persistService, org.mockito.Mockito.times(2)).persist(any(), anyList());
    }

    /**
     * 인덱스를 실어 던져야 <b>그 한 건만</b> DLT로 가고 나머지는 살아남는다.
     */
    @Test
    @DisplayName("계속 실패하는 한 건은 인덱스를 실어 격리한다")
    void 적재_불가_항목은_인덱스와_함께_격리된다() {
        List<EnqueueEvent> events = events(4);
        failOn(events.get(2).tokenId());

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(2);
    }

    /**
     * 인덱스는 <b>배치 전체 기준</b>이어야 한다. 구간(run) 안의 상대 위치를 실어 보내면
     * Spring이 엉뚱한 레코드를 DLT로 보내고, 진짜 범인은 무한 재처리된다.
     */
    @Test
    @DisplayName("앞 구간이 있어도 격리 인덱스는 배치 전체 기준이다")
    void 격리_인덱스는_배치_전체_기준이다() {
        List<EnqueueEvent> events = new ArrayList<>(events(4));
        events.set(2, withType("ADMITTED", events.get(2)));
        events.set(3, withType("ADMITTED", events.get(3)));
        failOn(events.get(3).tokenId());

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(3);
    }

    /**
     * 정상 배치는 한 번의 트랜잭션으로 끝난다 — 탐색이 끼어들지 않아야 한다.
     */
    @Test
    @DisplayName("정상 배치는 persist 1회로 끝난다")
    void 정상_배치는_한_번만_적재한다() {
        doNothing().when(persistService).persist(any(), anyList());

        consumer.consume(events(4));

        verify(persistService).persist(eq(TokenEventType.ENQUEUED), anyList());
    }

    /**
     * 판별 필드가 없는 <b>구 메시지</b>는 지금까지와 똑같이 적재돼야 한다.
     *
     * <p>토픽에 이미 쌓여 있는 것과 롤링 배포 중 구 프로듀서가 보내는 것이 여기 해당한다.
     * 미지 타입으로 취급하면 <b>백로그 전체가 DLT로</b> 간다.
     */
    @Test
    @DisplayName("판별 필드가 없는 구 메시지는 ENQUEUED로 읽어 그대로 적재한다")
    void 판별_필드가_없으면_ENQUEUED로_적재한다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> legacy = List.of(new EnqueueEvent(
                null, "tok_0", "q_test", 1L, "u0", 1L, Instant.ofEpochMilli(1L), null, null, null));

        assertThatCode(() -> consumer.consume(legacy)).doesNotThrowAnyException();

        verify(persistService).persist(eq(TokenEventType.ENQUEUED), anyList());
    }

    /**
     * <b>같은 타입이 연속하는 구간씩</b> 넘겨야 한다. 타입별로 모아 넘기면 같은 토큰의
     * {@code ADMITTED → COMPLETED} 순서가 뒤집혀, COMPLETED가 먼저 no-op이 되고
     * 그 토큰은 영원히 완료되지 않는다.
     */
    @Test
    @DisplayName("타입이 섞인 배치는 도착 순서대로 구간을 나눠 적재한다")
    void 타입이_섞이면_구간별로_순서대로_적재한다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(4));
        events.set(1, withType("ADMITTED", events.get(1)));
        events.set(2, withType("COMPLETED", events.get(2)));
        // [ENQUEUED, ADMITTED, COMPLETED, ENQUEUED] → 구간 4개

        consumer.consume(events);

        InOrder order = inOrder(persistService);
        order.verify(persistService).persist(eq(TokenEventType.ENQUEUED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.ADMITTED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.COMPLETED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.ENQUEUED), anyList());
        order.verifyNoMoreInteractions();
    }

    /** 연속한 같은 타입은 한 구간으로 묶여야 한다 — 건별로 쪼개지면 배치의 이점이 사라진다. */
    @Test
    @DisplayName("연속한 같은 타입은 한 번에 넘어간다")
    void 연속한_같은_타입은_한_구간이다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(4));
        events.set(2, withType("ADMITTED", events.get(2)));
        events.set(3, withType("ADMITTED", events.get(3)));

        consumer.consume(events);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Token>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistService).persist(eq(TokenEventType.ADMITTED), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    /** ADMITTED는 상태와 두 칸을 실어 넘어가야 한다 — 빠지면 complete의 술어가 영원히 안 맞는다. */
    @Test
    @DisplayName("ADMITTED는 status·admitToken·admittedAt을 실어 넘긴다")
    void ADMITTED는_admit_정보를_싣는다() {
        doNothing().when(persistService).persist(any(), anyList());

        Instant admittedAt = Instant.ofEpochMilli(1_700_000_009_000L);
        consumer.consume(List.of(new EnqueueEvent("ADMITTED", "tok_0", "q_test", 1L, "u0", 1L,
                Instant.ofEpochMilli(1_700_000_000_000L), "adm_0", admittedAt, null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Token>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistService).persist(eq(TokenEventType.ADMITTED), captor.capture());

        Token token = captor.getValue().get(0);
        assertThat(token.getStatus()).isEqualTo(TokenStatus.ADMIT_ISSUED);
        assertThat(token.getAdmitToken()).isEqualTo("adm_0");
        // UTC 고정 변환이라 인스턴스 TZ와 무관하게 같은 값이어야 한다
        assertThat(token.getAdmittedAt()).isEqualTo("2023-11-14T22:13:29");
    }

    /** 모르는 타입은 그 한 건만. 앞 구간은 적재하고 던진다. */
    @Test
    @DisplayName("모르는 타입은 앞쪽 정상 건을 적재한 뒤 그 한 건만 격리한다")
    void 모르는_타입은_한_건만_격리한다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(4));
        events.set(2, withType("WHAT_IS_THIS", events.get(2)));

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(2);

        // 앞쪽 2건은 적재하고 던진다. 던진 뒤에 적재하면 인덱스 앞이 "성공"으로 커밋돼 사라진다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Token>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistService).persist(eq(TokenEventType.ENQUEUED), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    /** 첫 건이 모르는 타입이면 적재할 앞 구간이 없다. */
    @Test
    @DisplayName("첫 건이 모르는 타입이면 persist 없이 격리한다")
    void 첫_건이_모르는_타입이면_적재하지_않는다() {
        List<EnqueueEvent> events = List.of(withType("WHAT_IS_THIS", events(1).get(0)));

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(0);

        verify(persistService, org.mockito.Mockito.never()).persist(any(), anyList());
    }

    // ---------------------------------------------------------------------

    /** 지정한 tokenId가 묶음에 들어 있으면 언제나 실패한다 = 절대 적재 불가 항목. */
    private void failOn(String offenderTokenId) {
        doAnswer(invocation -> {
            List<Token> tokens = invocation.getArgument(1);
            if (tokens.stream().anyMatch(t -> t.getTokenId().equals(offenderTokenId))) {
                throw new DataIntegrityViolationException("적재 불가");
            }
            return null;
        }).when(persistService).persist(any(), anyList());
    }

    private static EnqueueEvent withType(String eventType, EnqueueEvent e) {
        return new EnqueueEvent(eventType, e.tokenId(), e.queueId(), e.tenantId(), e.userId(),
                e.seq(), e.issuedAt(), e.admitToken(), e.admittedAt(), null);
    }

    private static List<EnqueueEvent> events(int count) {
        List<EnqueueEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new EnqueueEvent(
                    "ENQUEUED", "tok_" + i, "q_test", 1L, "u" + i, i + 1,
                    Instant.ofEpochMilli(1_700_000_000_000L + i), null, null, null));
        }
        assertThat(events).hasSize(count);
        return events;
    }
}
