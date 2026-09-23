package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code publishAll} 이 <b>전량 보낸 뒤 한 번에 기다리는지</b>.
 *
 * <p>계기는 AWS 12차 실측이다. admit 이 300건을 낼 때 p50 <b>1.797초</b>였고, 건당 5.99ms 는
 * {@code linger.ms}(5ms) + 브로커 왕복(1.38ms)과 정확히 맞았다 — <b>건별 ack 대기가 배칭을
 * 무력화</b>해 linger 를 건마다 전액 지불한 것이다.
 *
 * <p>🔑 여기서 지키는 성질은 둘이다: ① 실패 1건이 나머지를 데려가지 않는다(폭발 반경 N → 1)
 * ② 대기 예산을 전체가 공유한다(브로커 장애에 N × 타임아웃이 되지 않는다).
 */
class KafkaEnqueueEventPublisherBatchTest {

    private static final String TOPIC = "token-lifecycle";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

    private KafkaEnqueueEventPublisher publisher(long timeoutMs) {
        return new KafkaEnqueueEventPublisher(template, TOPIC, timeoutMs);
    }

    private EnqueueEvent event(String tokenId) {
        return new EnqueueEvent("ADMITTED", tokenId, "q_1", 1L, "u_" + tokenId, 1L,
                Instant.EPOCH, "adm_" + tokenId, Instant.EPOCH, null);
    }

    private CompletableFuture<SendResult<String, Object>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SendResult<String, Object>> broken() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
    }

    @Test
    @DisplayName("전부 성공하면 실패 0 — send 는 건수만큼, 키는 tokenId 다")
    void allSucceed() {
        when(template.send(eq(TOPIC), any(String.class), any())).thenReturn(ok());

        int failed = publisher(3000).publishAll(List.of(event("t1"), event("t2"), event("t3")));

        assertThat(failed).isZero();
        verify(template, times(3)).send(eq(TOPIC), any(String.class), any());
        verify(template).send(TOPIC, "t2", event("t2"));   // 키가 tokenId 여야 순서 보장이 선다
    }

    @Test
    @DisplayName("🔴 중간 1건이 실패해도 나머지는 전부 보낸다 — 실패는 1로만 센다")
    void oneFailureDoesNotTakeTheRest() {
        when(template.send(eq(TOPIC), eq("t1"), any())).thenReturn(ok());
        when(template.send(eq(TOPIC), eq("t2"), any())).thenReturn(broken());
        when(template.send(eq(TOPIC), eq("t3"), any())).thenReturn(ok());

        int failed = publisher(3000).publishAll(List.of(event("t1"), event("t2"), event("t3")));

        assertThat(failed).isEqualTo(1);
        // 🪤 예전 구조(첫 실패에서 break)라면 t3 는 send 조차 안 됐다. 그것이 이 단정의 전부다.
        verify(template).send(TOPIC, "t3", event("t3"));
    }

    @Test
    @DisplayName("send 가 동기로 던져도(버퍼 포화) 뒤 레코드는 계속 시도한다")
    void syncThrowIsCountedAndLoopContinues() {
        when(template.send(eq(TOPIC), eq("t1"), any()))
                .thenThrow(new IllegalStateException("buffer full"));
        when(template.send(eq(TOPIC), eq("t2"), any())).thenReturn(ok());

        int failed = publisher(3000).publishAll(List.of(event("t1"), event("t2")));

        assertThat(failed).isEqualTo(1);
        verify(template).send(TOPIC, "t2", event("t2"));
    }

    /**
     * 🔑 <b>대기 예산 공유의 본증명.</b> 건별로 타임아웃을 주면 브로커가 죽었을 때
     * 300건 × 12초(queue-api 의 {@code send-timeout-ms}) = <b>1시간</b>을 잡는다.
     * 전체가 하나를 공유하면 300건이어도 한 번의 예산이다.
     * 🪤 이 테스트는 <b>대기 구간만</b> 잰다 — 목이라 {@code send()} 자체의 블로킹
     *    ({@code max.block.ms} 4초)은 재지 못한다. 그 구간은 구현이 같은 데드라인으로 묶는다.
     *
     * <p>영원히 끝나지 않는 future 로 브로커 무응답을 만들고, 전체 소요가 예산의 몇 배인지 본다.
     */
    @Test
    @DisplayName("🔴 브로커가 무응답이면 전체 대기가 예산 1회분에 머문다 — 건수에 비례하지 않는다")
    void deadlineIsSharedAcrossRecords() {
        when(template.send(eq(TOPIC), any(String.class), any()))
                .thenReturn(new CompletableFuture<>());   // 절대 완료되지 않는다
        List<EnqueueEvent> events = IntStream.range(0, 20)
                .mapToObj(i -> event("t" + i)).toList();

        long t0 = System.nanoTime();
        int failed = publisher(300).publishAll(events);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(failed).isEqualTo(20);               // 모름은 실패로 센다
        // 🪤 상한을 건별 예산의 기댓값(20 × 300 = 6,000ms)에 붙이지 마라 — "예산을 10건마다
        //    리셋"처럼 **절반만 공유되는** 회귀가 3,000ms 로 통과한다.
        // 🔑 상한을 구조로 쓴다: 예산 1회분 + 건당 1ms. 데드라인이 지난 뒤 남은 future 마다
        //    `Math.max(1L, ...)` 가 1ms 를 주므로 **건수에 비례하는 꼬리**가 정상적으로 있다
        //    (실측 20건에 334~341ms). 숫자를 키우면 회귀를 놓치고, 고정값이면 건수를 늘리는
        //    변경에서 거짓 실패가 난다.
        assertThat(elapsedMs).isLessThan(300 + events.size() * 5L);
    }

    /**
     * 이유: <b>예산이 {@code send} 루프도 묶는지</b>. 이것이 이 변경의 안전 근거다.
     * 문제: {@code send()} 는 비동기가 아니다 — 메타데이터 미도달·버퍼 포화에서 {@code max.block.ms}(4초)를
     *       <b>건당</b> 전액 쓴다. 묶지 않으면 count=300 이 최대 <b>20분</b> 요청 스레드를 잡는다.
     * 원인: 위 {@code deadlineIsSharedAcrossRecords} 는 목이 즉시 반환해 <b>대기 루프만</b> 잰다 —
     *       send 구간은 마이크로초에 지나가 이 분기를 **한 번도 밟지 않는다**(2026-09-23 주입 실측:
     *       break 를 지워도 전 레인 초록이었다).
     * 해결: {@code send} 를 느리게 만들어 예산이 <b>루프 도중</b> 소진되게 한다.
     * 🔑 예전 코드(첫 실패에서 break)의 최악이 ~16초였다 — 이 분기를 잃으면 <b>최악이 더 나빠진다</b>.
     *
     * @author sonix
     */
    @Test
    @DisplayName("🔴 send 가 느리면 예산에서 끊고 남은 건은 보내지 않는다 — 20분 점유를 막는 분기")
    void deadlineAlsoBoundsTheSendLoop() {
        when(template.send(eq(TOPIC), any(String.class), any())).thenAnswer(inv -> {
            Thread.sleep(40);        // send() 가 max.block 을 태우는 상황
            return ok();
        });
        List<EnqueueEvent> events = IntStream.range(0, 50)
                .mapToObj(i -> event("t" + i)).toList();

        long t0 = System.nanoTime();
        int failed = publisher(300).publishAll(events);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 300ms 예산 / 건당 40ms → 8건 안팎에서 끊긴다. 전건을 보내면 2,000ms 다.
        verify(template, atMost(20)).send(eq(TOPIC), any(String.class), any());
        assertThat(failed).as("보내지 못한 건은 실패로 센다").isPositive();
        assertThat(elapsedMs).as("예산을 크게 넘기지 않는다").isLessThan(1_000L);
    }
}
