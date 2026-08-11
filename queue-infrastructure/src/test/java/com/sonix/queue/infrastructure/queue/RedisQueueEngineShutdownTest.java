package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종료 중 도착한 enqueue의 실패 경로 검증.
 *
 * <p>마지막 drain이 끝난 뒤 들어온 요청은 처리해 줄 주체가 없다. 이때 30초를 매달렸다
 * 503이 되면 웹 graceful 대기 창(20s)을 넘겨 커넥션이 그냥 끊긴다 — 호출자는 성공·실패를
 * 구분하지 못한다. 즉시 실패해야 재시도할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class RedisQueueEngineShutdownTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<List> enqueueBulkScript;

    @Mock
    private RedisScript<Long> pollVerifyScript;

    @Test
    @DisplayName("종료 표시 후 도착한 enqueue는 대기 없이 즉시 실패하고 Global Queue에 남지 않는다")
    void enqueueAfterShutdown_failsFastAndLeavesNothingBehind() {
        RedisQueueEngine engine = new RedisQueueEngine(redisTemplate, enqueueBulkScript, pollVerifyScript);
        engine.markShuttingDown();

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> engine.enqueue("q_dev_shutdown", "u1"))
                .isInstanceOf(BusinessException.class);

        // MAX_WAIT_SECONDS(30s)를 기다리지 않는다
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        // 회수까지 마쳐 다음 drain 대상으로 남지 않는다
        assertThat(engine.getGlobalQueue()).isEmpty();
    }
}
