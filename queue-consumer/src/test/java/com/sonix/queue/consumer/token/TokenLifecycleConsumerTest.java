package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private static List<EnqueueEvent> events(int count) {
        List<EnqueueEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new EnqueueEvent(
                    "tok_" + i, "q_test", 1L, "u" + i, i + 1, Instant.ofEpochMilli(1_700_000_000_000L + i)));
        }
        assertThat(events).hasSize(count);
        return events;
    }
}
