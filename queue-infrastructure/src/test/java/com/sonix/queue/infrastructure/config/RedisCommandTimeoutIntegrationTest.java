package com.sonix.queue.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Lettuce {@code commandTimeout}이 <b>실제로</b> 5초로 걸려 있는지 실 Redis에 붙어 확인한다.
 *
 * <p><b>왜 필요한가:</b> 종료 경로의 상한(≈10s)은 "drain 데드라인 5s + Redis 커맨드 5s"라는
 * 두 값의 합으로만 성립한다. Redis 쪽 시한이 다시 기본값(60s)으로 돌아가면 종료 상한이
 * 조용히 사라지는데, 그건 평시 테스트로는 절대 드러나지 않는다(정상 응답은 밀리초 단위라
 * 5s든 60s든 결과가 같다). 그래서 <b>일부러 응답하지 않는 커맨드</b>로 시한 자체를 잰다.
 *
 * <p><b>공유 인프라를 깨지 않는 방법:</b> {@code DEBUG SLEEP}·{@code CLIENT PAUSE}는 서버 전체를
 * 멈춰 다른 에이전트의 검증까지 깨뜨린다. 대신 아무도 쓰지 않는 키에 {@code BLPOP}을 건다 —
 * 블록되는 건 이 테스트의 커넥션 하나뿐이고, 서버와 다른 클라이언트는 영향받지 않는다.
 * 키를 만들지도 않으므로 정리할 데이터도 남지 않는다.
 */
@SpringBootTest(classes = com.sonix.queue.infrastructure.queue.QueueEngineRedisTestConfig.class)
@Tag("redis")
class RedisCommandTimeoutIntegrationTest {

    /** 아무도 쓰지 않는 키. BLPOP은 키를 생성하지 않으므로 정리 대상이 남지 않는다. */
    private static final String IDLE_KEY = "q_test_cmdtimeout:never-pushed";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("Lettuce commandTimeout이 5초로 설정돼 있다 (기본 60초가 아님)")
    void commandTimeoutIsFiveSeconds() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        Duration configured = ((LettuceConnectionFactory) connectionFactory)
                .getClientConfiguration().getCommandTimeout();

        assertThat(configured)
                .as("Lettuce 기본값 60s로 돌아가면 종료 상한(≈10s)이 사라진다")
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("응답하지 않는 커맨드는 60초가 아니라 5초대에서 끊긴다 (실 Redis)")
    void unresponsiveCommandIsCutAtFiveSeconds() {
        // BLPOP 30s: 아무도 push하지 않으므로 서버는 30초 동안 응답하지 않는다.
        // commandTimeout이 걸려 있으면 5초쯤에 클라이언트가 먼저 끊는다.
        long t0 = System.nanoTime();
        Throwable thrown = catchThrowable(
                () -> redisTemplate.opsForList().leftPop(IDLE_KEY, Duration.ofSeconds(30)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.printf("[cmd-timeout] elapsed=%dms, thrown=%s%n",
                elapsedMs, thrown == null ? "none" : thrown.getClass().getSimpleName());

        assertThat(thrown)
                .as("commandTimeout이 안 걸렸다면 예외 없이 30초 뒤 null이 돌아온다")
                .isNotNull();
        // 4s~8s: 5s 시한이 실제로 동작한다는 증거. 30s(BLPOP 자연 만료)나
        // 60s(Lettuce 기본값)와는 명확히 구분된다.
        assertThat(elapsedMs).isBetween(4_000L, 8_000L);

        // 키를 만들지 않았음을 확인 — 공유 Redis에 흔적을 남기지 않는다
        assertThat(redisTemplate.hasKey(IDLE_KEY)).isFalse();
    }
}
