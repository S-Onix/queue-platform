package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * 종료 표시가 <b>fast path 검사를 통과한 직후</b>에 켜지는 경합 구간 검증.
 *
 * <p><b>왜 별도 테스트가 필요한가:</b> {@code enqueue()}에는 shuttingDown 검사가 두 군데 있다 —
 * offer 앞(fast path, 비용 방어)과 offer 뒤(remove 성공 = 아직 아무도 안 가져갔다는 증거, 정합성).
 * 기존 {@code RedisQueueEngineShutdownTest}는 두 검사가 <b>모두 켜진 상태</b>에서 markShuttingDown을
 * 먼저 호출하므로, 둘 중 <b>어느 하나만 남겨도 통과</b>한다(실측: 각각 지운 변이 모두 통과).
 * 즉 정합성을 책임지는 offer 뒤 검사가 아무 테스트에도 고정돼 있지 않았다.
 *
 * <p><b>재현 방법:</b> 진짜 스레드 경합은 비결정적이라 회귀 탐지선으로 못 쓴다.
 * fast path와 offer 사이에 있는 유일한 호출 지점({@code IdGenerator.generate})을 스텁해
 * "검사 통과 → 종료 시작 → offer" 순서를 결정적으로 만든다.
 *
 * <p>offer 뒤 검사가 사라지면 이 요청은 아무도 처리하지 않는 Global Queue에 남아
 * MAX_WAIT_SECONDS(30s)를 매달렸다 실패한다 — 그래서 예외 타입만이 아니라 <b>경과 시간</b>도 본다.
 */
@ExtendWith(MockitoExtension.class)
class RedisQueueEngineShutdownRaceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<List> enqueueBulkScript;

    @Mock
    private RedisScript<Long> pollVerifyScript;

    @Mock
    private RedisScript<List> admitScript;

    @Mock
    private RedisScript<List> admitExpireScript;
    @Mock
    private RedisScript<List> inactiveExpireScript;

    @Test
    @DisplayName("fast path 통과 직후 종료가 시작돼도 enqueue는 대기 없이 실패하고 큐에 흔적을 남기지 않는다")
    void enqueue_whenShutdownStartsAfterFastPath_stillFailsFastAndLeavesNothingBehind() {
        RedisQueueEngine engine = new RedisQueueEngine(redisTemplate, enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript, inactiveExpireScript);

        try (MockedStatic<IdGenerator> ids = mockStatic(IdGenerator.class)) {
            // fast path 검사를 통과한 뒤, offer 하기 전에 종료가 시작된 상황
            ids.when(() -> IdGenerator.generate("tok_")).thenAnswer(inv -> {
                engine.markShuttingDown();
                return "tok_race";
            });

            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> engine.enqueue("q_test_race", "u1"))
                    .isInstanceOf(BusinessException.class);

            // MAX_WAIT_SECONDS(30s)를 기다리지 않는다 = offer 뒤 검사가 잡아냈다는 증거
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        }

        // 거절된 요청은 Global Queue에 남지 않는다(= Redis에도 흔적이 생기지 않는다)
        assertThat(engine.getGlobalQueue()).isEmpty();
    }
}
