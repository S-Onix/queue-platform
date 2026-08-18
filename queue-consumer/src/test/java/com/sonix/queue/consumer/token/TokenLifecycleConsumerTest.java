package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 제약 위반이 났을 때 <b>무엇을 DLT로 보내고 무엇을 보내지 않는가</b>를 고정한다.
 *
 * <p>이 경로는 로컬에서 재현하기 어렵다. 적재가 멱등({@code ON DUPLICATE KEY})이라
 * 중복으로는 제약 위반이 나지 않고, 길이 초과 같은 실제 위반은 부하 테스트에 섞여 들어오지
 * 않기 때문이다. 그래서 오동작이 <b>운영에서 데이터가 사라진 뒤에야</b> 드러난다 —
 * 테스트로 못박아 두는 이유다.
 *
 * <p>판별 필드({@code eventType})가 붙은 뒤로는 <b>처리할 수 없는 타입</b>도 같은 질문을 만든다 —
 * 구 메시지는 그대로 적재하고, 처리기 없는 타입은 한 건만 격리한다.
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
                .when(persistService).persist(anyList());

        assertThatCode(() -> consumer.consume(events(4)))
                .doesNotThrowAnyException();

        // 최초 1회 + 범인 탐색의 재시도 1회
        verify(persistService, org.mockito.Mockito.times(2)).persist(anyList());
    }

    /**
     * 인덱스를 실어 던져야 <b>그 한 건만</b> DLT로 가고 나머지는 살아남는다.
     */
    @Test
    @DisplayName("계속 실패하는 한 건은 인덱스를 실어 격리한다")
    void 적재_불가_항목은_인덱스와_함께_격리된다() {
        List<EnqueueEvent> events = events(4);
        String offenderTokenId = events.get(2).tokenId();

        doAnswer(invocation -> {
            List<Token> tokens = invocation.getArgument(0);
            boolean hasOffender = tokens.stream()
                    .anyMatch(t -> t.getTokenId().equals(offenderTokenId));
            if (hasOffender) {
                throw new DataIntegrityViolationException("적재 불가");
            }
            return null;
        }).when(persistService).persist(anyList());

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(2);
    }

    /**
     * 정상 배치는 한 번의 트랜잭션으로 끝난다 — 탐색이 끼어들지 않아야 한다.
     */
    @Test
    @DisplayName("정상 배치는 persist 1회로 끝난다")
    void 정상_배치는_한_번만_적재한다() {
        doNothing().when(persistService).persist(anyList());

        consumer.consume(events(4));

        verify(persistService).persist(anyList());
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
        doNothing().when(persistService).persist(anyList());

        List<EnqueueEvent> legacy = List.of(
                new EnqueueEvent(null, "tok_0", "q_test", 1L, "u0", 1L, Instant.ofEpochMilli(1L)));

        assertThatCode(() -> consumer.consume(legacy)).doesNotThrowAnyException();

        verify(persistService).persist(anyList());
    }

    /**
     * 아직 처리기가 없는 타입(Sprint 7에서 붙는다)을 enqueue처럼 적재하면
     * <b>ADMIT_ISSUED가 WAITING으로 되감긴다</b> — 조용히 삼키지 말고 격리한다.
     */
    @Test
    @DisplayName("처리기가 없는 타입은 앞쪽 정상 건을 적재한 뒤 그 한 건만 격리한다")
    void 처리기가_없는_타입은_한_건만_격리한다() {
        doNothing().when(persistService).persist(anyList());

        List<EnqueueEvent> events = new ArrayList<>(events(4));
        events.set(2, withType("ADMITTED", events.get(2)));

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(2);

        // 앞쪽 2건은 적재하고 던진다. 던진 뒤에 적재하면 인덱스 앞이 "성공"으로 커밋돼 사라진다.
        ArgumentCaptor<List<Token>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistService).persist(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    /** 모르는 타입도 같은 조치다 — 인덱스를 실어 그 한 건만. */
    @Test
    @DisplayName("모르는 타입은 persist 없이 그 한 건만 격리한다")
    void 모르는_타입은_한_건만_격리한다() {
        List<EnqueueEvent> events = List.of(withType("WHAT_IS_THIS", events(1).get(0)));

        assertThatThrownBy(() -> consumer.consume(events))
                .isInstanceOf(BatchListenerFailedException.class)
                .extracting(e -> ((BatchListenerFailedException) e).getIndex())
                .isEqualTo(0);

        verify(persistService, org.mockito.Mockito.never()).persist(anyList());
    }

    private static EnqueueEvent withType(String eventType, EnqueueEvent e) {
        return new EnqueueEvent(
                eventType, e.tokenId(), e.queueId(), e.tenantId(), e.userId(), e.seq(), e.issuedAt());
    }

    private static List<EnqueueEvent> events(int count) {
        List<EnqueueEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new EnqueueEvent(
                    "ENQUEUED", "tok_" + i, "q_test", 1L, "u" + i, i + 1,
                    Instant.ofEpochMilli(1_700_000_000_000L + i)));
        }
        assertThat(events).hasSize(count);
        return events;
    }
}
