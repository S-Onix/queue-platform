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
import static org.mockito.Mockito.calls;
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
     *
     * <p><b>중복 tokenId를 일부러 심는다.</b> 이 단정이 겨냥하는 것은 {@code findOffendingIndex}가
     * -1을 돌려줬을 때의 분기인데, 배치가 그룹 적재 가능하면 <b>첫 실패를 그룹 경로가 삼키고</b>
     * 구간 분할이 성공해 버려 그 분기에 닿지 않는다. 호출 횟수(2회)가 우연히 같아서
     * <b>초록인 채로 검증 대상이 사라진다</b> — 그래서 그룹 경로를 못 타게 막아 둔다.
     */
    @Test
    @DisplayName("범인을 특정하지 못하면(재시도 중 전 건 적재) 예외 없이 ack 한다")
    void 범인을_특정하지_못하면_예외_없이_반환한다() {
        // 첫 시도만 실패하고 이후 시도는 성공 = 일시적 원인이 해소된 상황
        doThrow(new DataIntegrityViolationException("일시적"))
                .doNothing()
                .when(persistService).persist(any(), anyList());

        assertThatCode(() -> consumer.consume(withDuplicateTokenId(events(4))))
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
        // 정상 운영의 대부분이 이 모양이다 — 트랜잭션이 하나를 넘으면 안 된다
        verify(persistService, org.mockito.Mockito.times(1)).persist(any(), anyList());
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
     * <b>같은 토큰이 한 배치에 두 번 실리면</b> 도착 순서를 그대로 지켜야 한다. 타입별로 모아
     * 넘기면 그 순서가 뒤집혀, 가드({@code status = 1}에서만)가 거짓인 COMPLETED가 먼저
     * no-op이 되고 그 토큰은 영원히 완료되지 않는다.
     *
     * <p><b>도착 순서를 일부러 전이 순서와 반대로 둔다.</b> 그룹 적재는 {@code EnumMap}의
     * 선언 순서(ENQUEUED→ADMITTED→COMPLETED→EXPIRED)로 도는데, 도착이 이미 그 순서면
     * 두 경로의 결과가 같아 <b>구간 분할을 탔는지 아닌지 구분할 수 없다</b>. 반대로 두어야
     * "정렬되지 않고 도착 순서가 보존됐다"가 관측 가능해진다.
     *
     * <p>🔴 이 테스트는 원래 {@code tokenId}가 전부 다른 데이터로 순서를 검증하고 있었다.
     * 서로 다른 토큰은 순서가 뒤집혀도 아무 일이 일어나지 않으므로, 지키려는 성질을
     * 겨냥하지 못한 채 <b>구현 방식(구간 분할)만</b> 고정하고 있었다.
     */
    @Test
    @DisplayName("같은 토큰이 두 번 실린 배치는 타입별로 모으지 않고 도착 순서를 지킨다")
    void 같은_토큰이_두_번이면_도착_순서를_지킨다() {
        doNothing().when(persistService).persist(any(), anyList());

        EnqueueEvent base = events(1).get(0);
        List<EnqueueEvent> events = List.of(
                withType("COMPLETED", base),
                withType("ADMITTED", base));   // 같은 tokenId가 두 번

        consumer.consume(events);

        InOrder order = inOrder(persistService);
        order.verify(persistService).persist(eq(TokenEventType.COMPLETED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.ADMITTED), anyList());
        order.verifyNoMoreInteractions();
    }

    /**
     * 중복 {@code tokenId}가 없으면 순서가 깨질 대상 자체가 없으므로 타입별로 모은다 —
     * <b>타입당 1회</b>. 구간 분할은 타입이 바뀔 때마다 트랜잭션(= 커밋 fsync 2회)을 열어,
     * 혼합 부하에서 커밋이 폭주한 실측이 이 경로의 근거다 — 2026-08-27 반사실 측정으로
     * 커밋 76,806 → 13,080(절감 83%)을 확인했다. 단 저부하에서는 절감 0%다(본체 주석 참조).
     *
     * <p>실행 순서는 {@code EnumMap}의 <b>enum 선언 순서</b>다. 도착을 전이 순서의 역순으로
     * 넣어 두었으므로, 선언 순서를 뒤섞으면 이 단정이 곧바로 빨개진다.
     */
    @Test
    @DisplayName("중복 tokenId가 없는 혼합 배치는 타입당 1회로 모아 전이 순서대로 적재한다")
    void 중복이_없으면_타입당_한_번_전이_순서로_적재한다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(5));
        events.set(0, withType("COMPLETED", events.get(0)));
        events.set(1, withType("EXPIRED", events.get(1)));
        events.set(2, withType("ADMITTED", events.get(2)));
        events.set(3, withType("COMPLETED", events.get(3)));
        // 도착 [COMPLETED, EXPIRED, ADMITTED, COMPLETED, ENQUEUED] → 구간 분할이면 5회

        consumer.consume(events);

        InOrder order = inOrder(persistService);
        order.verify(persistService).persist(eq(TokenEventType.ENQUEUED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.ADMITTED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.COMPLETED), anyList());
        order.verify(persistService).persist(eq(TokenEventType.EXPIRED), anyList());
        order.verifyNoMoreInteractions();

        // 같은 타입은 한 번에 — COMPLETED 두 건이 두 트랜잭션으로 쪼개지면 모은 의미가 없다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Token>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistService).persist(eq(TokenEventType.COMPLETED), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    /**
     * 그룹 적재가 제약 위반으로 실패하면 <b>같은 배치를 구간 분할로 다시 태운다</b>.
     * 그룹 경로에서는 격리 인덱스(원본 배치 기준)를 특정할 수 없으므로, 범인 탐색을 할 수 있는
     * 경로로 넘겨야 한다. 적재가 멱등이라 재실행이 안전하다는 전제가 여기 걸려 있다.
     *
     * <p>재실행이 없으면 <b>제약 위반이 그대로 전파되어 배치 전체가 DLT로</b> 간다.
     */
    @Test
    @DisplayName("그룹 적재가 제약 위반이면 같은 배치를 구간 분할로 다시 태운다")
    void 그룹_적재가_실패하면_구간_분할로_재실행한다() {
        // 첫 호출(= 그룹 경로의 첫 그룹)만 실패
        doThrow(new DataIntegrityViolationException("일시적"))
                .doNothing()
                .when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(3));
        events.set(1, withType("ADMITTED", events.get(1)));
        // 도착 [ENQUEUED, ADMITTED, ENQUEUED] — 구간 분할이면 3회

        assertThatCode(() -> consumer.consume(events)).doesNotThrowAnyException();

        // 같은 인자의 검증이 연속하므로 calls(1)을 쓴다. 기본 times(1)은 InOrder에서 탐욕적이라
        // 연속한 동일 호출을 한 번에 삼켜 "3번 불렸다"로 실패한다.
        InOrder order = inOrder(persistService);
        order.verify(persistService, calls(1)).persist(eq(TokenEventType.ENQUEUED), anyList()); // 실패한 그룹
        order.verify(persistService, calls(1)).persist(eq(TokenEventType.ENQUEUED), anyList()); // 구간 1
        order.verify(persistService, calls(1)).persist(eq(TokenEventType.ADMITTED), anyList()); // 구간 2
        order.verify(persistService, calls(1)).persist(eq(TokenEventType.ENQUEUED), anyList()); // 구간 3
        order.verifyNoMoreInteractions();
    }

    /**
     * 연속한 같은 타입은 한 구간으로 묶여야 한다 — 건별로 쪼개지면 배치의 이점이 사라진다.
     *
     * <p>중복 {@code tokenId}를 심어 <b>구간 분할 경로</b>를 강제한다. 안 그러면 그룹 경로로
     * 새어 나가 "타입별로 모았으니 2건"이 되어, 구간 병합을 검증하지 않는데도 초록이 된다.
     */
    @Test
    @DisplayName("연속한 같은 타입은 한 번에 넘어간다")
    void 연속한_같은_타입은_한_구간이다() {
        doNothing().when(persistService).persist(any(), anyList());

        List<EnqueueEvent> events = withDuplicateTokenId(new ArrayList<>(events(4)));
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

        // 모르는 타입이 하나라도 있으면 그룹 경로를 타면 안 된다. 탔다면 뒤쪽 ENQUEUED까지
        // 함께 적재되어(또는 null 키로 터져) 격리 인덱스를 잃는다.
        verify(persistService, org.mockito.Mockito.times(1)).persist(any(), anyList());
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

    /**
     * 마지막 건의 {@code tokenId}를 첫 건과 같게 만들어 <b>그룹 적재 대상에서 제외</b>시킨다.
     * (컨슈머는 같은 {@code tokenId}가 두 번 이상이면 구간 분할로 내려간다.)
     */
    private static List<EnqueueEvent> withDuplicateTokenId(List<EnqueueEvent> events) {
        List<EnqueueEvent> copy = new ArrayList<>(events);
        EnqueueEvent last = copy.get(copy.size() - 1);
        copy.set(copy.size() - 1, new EnqueueEvent(last.eventType(), copy.get(0).tokenId(),
                last.queueId(), last.tenantId(), last.userId(), last.seq(), last.issuedAt(),
                last.admitToken(), last.admittedAt(), null));
        return copy;
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
