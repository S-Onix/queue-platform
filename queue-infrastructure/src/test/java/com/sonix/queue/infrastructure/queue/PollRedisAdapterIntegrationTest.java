package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.QueueSnapshot;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폴링 어댑터 통합 테스트 (실제 Redis).
 *
 * <p><b>대기자 1만명 규모</b>로 waiting ZSet을 seed하고, 폴링이 쓰는 3개 Redis 연산
 * ({@code readSnapshot} / {@code isWaiting} / {@code touchLastActive})을 검증한다.
 * enqueue 경로를 우회하고 ZSet에 직접 seed하므로 tenant/DB가 필요 없다
 * (poll이 public·Redis-only인 특성 그대로).
 *
 * <p>로컬 Redis에 연결하며, 테스트 키는 각 테스트 전후로 정리한다.
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
public class PollRedisAdapterIntegrationTest {

    @Autowired private RedisQueueEngine queueEngine;
    @Autowired private StringRedisTemplate redisTemplate;

    private static final String QUEUE_ID = "test_q_poll";
    private static final String WAITING_KEY = QueueKeys.waiting(QUEUE_ID);
    private static final String LAST_ACTIVE_KEY = QueueKeys.lastActive(QUEUE_ID);
    private static final int WAITERS = 10_000;

    /** 대기자 1만명: member=user_i, score=seq=i (seq 1..10000). 한 번의 batch ZADD로 seed. */
    @BeforeEach
    void seed() {
        redisTemplate.delete(WAITING_KEY);
        redisTemplate.delete(LAST_ACTIVE_KEY);

        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>(WAITERS * 2);
        for (int i = 1; i <= WAITERS; i++) {
            tuples.add(new DefaultTypedTuple<>("user_" + i, (double) i));
        }
        redisTemplate.opsForZSet().add(WAITING_KEY, tuples);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete(WAITING_KEY);
        redisTemplate.delete(LAST_ACTIVE_KEY);
    }

    @Test
    @DisplayName("readSnapshot: 1만 대기 시 frontSeq=최소seq(1), total=10000")
    void readSnapshot_returnsFrontSeqAndTotal() {
        QueueSnapshot snap = queueEngine.readSnapshot(QUEUE_ID);

        assertThat(snap.frontSeq()).isEqualTo(1L);
        assertThat(snap.total()).isEqualTo(WAITERS);
    }

    @Test
    @DisplayName("readSnapshot: 맨앞이 빠지면(admit) frontSeq가 다음 최소로 전진")
    void readSnapshot_frontAdvancesAfterRemoval() {
        redisTemplate.opsForZSet().remove(WAITING_KEY, "user_1", "user_2", "user_3");

        QueueSnapshot snap = queueEngine.readSnapshot(QUEUE_ID);

        assertThat(snap.frontSeq()).isEqualTo(4L);          // 1,2,3 빠짐 → 4가 front
        assertThat(snap.total()).isEqualTo(WAITERS - 3);
    }

    @Test
    @DisplayName("readSnapshot: 빈 큐면 frontSeq=-1, total=0")
    void readSnapshot_emptyQueue() {
        redisTemplate.delete(WAITING_KEY);

        QueueSnapshot snap = queueEngine.readSnapshot(QUEUE_ID);

        assertThat(snap.frontSeq()).isEqualTo(-1L);
        assertThat(snap.total()).isZero();
    }

    @Test
    @DisplayName("isWaiting: 존재하는 seq면 true, 없는 seq면 false")
    void isWaiting_presence() {
        assertThat(queueEngine.isWaiting(QUEUE_ID, 1)).isTrue();
        assertThat(queueEngine.isWaiting(QUEUE_ID, 5000)).isTrue();
        assertThat(queueEngine.isWaiting(QUEUE_ID, WAITERS)).isTrue();        // 10000 존재
        assertThat(queueEngine.isWaiting(QUEUE_ID, WAITERS + 1)).isFalse();   // 10001 없음
        assertThat(queueEngine.isWaiting(QUEUE_ID, 0)).isFalse();
    }

    @Test
    @DisplayName("touchLastActive: last-active ZSet에 seq가 score=now로 기록된다")
    void touchLastActive_recordsSeqWithTimestamp() {
        long now = 1_700_000_000_000L;

        queueEngine.touchLastActive(QUEUE_ID, 5000, now);

        Double score = redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, "5000");
        assertThat(score).isNotNull();
        assertThat(score.longValue()).isEqualTo(now);
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isEqualTo(1L);
    }

    @Test
    @DisplayName("touchLastActive: 재호출 시 같은 seq의 score(now)만 갱신, 건수 그대로")
    void touchLastActive_updatesScore() {
        queueEngine.touchLastActive(QUEUE_ID, 5000, 1000L);
        queueEngine.touchLastActive(QUEUE_ID, 5000, 2000L);

        Double score = redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, "5000");
        assertThat(score.longValue()).isEqualTo(2000L);
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isEqualTo(1L);
    }
}
