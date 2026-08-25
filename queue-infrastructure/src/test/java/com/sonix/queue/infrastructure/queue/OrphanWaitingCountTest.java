package com.sonix.queue.infrastructure.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좀비(고아) 집계 검증 — 실제 Redis (§80 U9).
 *
 * <p><b>고아 = {@code waiting}에 있는데 {@code tokens} Hash에 없는 사람.</b>
 * {@code admit.lua}가 {@code HGET} 미스로 되돌려 놓는 조건 그대로다. 그 사람은 매 admit 주기마다
 * 뽑혔다 되돌아가며 슬롯을 먹고, admit은 그를 지나가지 못한다.
 *
 * <p><b>이 테스트가 없으면 무엇이 깨지나:</b> 판정은 {@code RedisQueueEngine} 안에만 살고, 잡 쪽
 * 단위 테스트는 {@code QueueEngine}을 목으로 둬 그 로직을 한 줄도 실행하지 않는다.
 * 판정 기준이 틀리면 <b>전부 초록인 채로</b> 알람이 상시 발화하거나 상시 침묵한다.
 * 실제로 그런 일이 있었다 — 아래 {@link #doesNotCountHealthyMembers} 참조.
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
@Tag("redis")
class OrphanWaitingCountTest {

    private static final String QUEUE_ID = "q_test_orphan";
    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String WATERMARK = QueueKeys.admitWatermark(QUEUE_ID);

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisQueueEngine engine;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 세 키 모두 {queueId} 해시태그라 같은 슬롯이다 — 다중 키 DEL이 Cluster에서도 성립한다.
        redis.delete(List.of(WAITING, TOKENS, WATERMARK));
    }

    /** 정상 대기자: waiting에 자리 + tokens에 게이트({@code "tokenId|issuedAt"}). */
    private void healthy(String identifier, long seq) {
        redis.opsForZSet().add(WAITING, identifier, seq);
        redis.opsForHash().put(TOKENS, identifier, "tok_" + identifier + "|1700000000000");
    }

    /** 고아: waiting에만 있고 tokens Hash 항목이 없다. */
    private void orphan(String identifier, long seq) {
        redis.opsForZSet().add(WAITING, identifier, seq);
    }

    @Test
    @DisplayName("tokens Hash 항목이 없는 waiting 멤버만 센다 (U9)")
    void countsMembersWithoutTokensHashEntry() {
        orphan("u1", 1);
        orphan("u2", 2);
        healthy("u3", 3);
        healthy("u4", 4);

        assertThat(engine.countOrphanedWaiting(QUEUE_ID)).isEqualTo(2L);
    }

    /**
     * 🔴 <b>실측으로 발견된 오탐의 검출기</b> (2026-08-24).
     *
     * <p>초안은 "admit watermark보다 앞 순번인데 waiting에 있다"로 판정했다. dev Redis에서
     * <b>15,144건이 전부 오탐</b>이었다 — {@code admit-watermark}가 {@code waiting}과 갈라져
     * 있었기 때문이다(부하 테스트가 waiting을 재시드하며 watermark는 안 지웠다).
     * 여기 멤버들은 watermark보다 <b>훨씬 앞</b>이지만 {@code tokens}가 멀쩡하므로 <b>0</b>이어야 한다.
     * 위치 기반 판정이 다시 들어오면 이 테스트가 빨개진다.
     */
    @Test
    @DisplayName("watermark보다 앞 순번이어도 tokens Hash가 있으면 안 센다 — 위치는 증거가 아니다 (U9)")
    void doesNotCountHealthyMembers() {
        healthy("u1", 1);
        healthy("u2", 2);
        healthy("u3", 3);
        redis.opsForValue().set(WATERMARK, "99999");   // 갈라진 watermark

        assertThat(engine.countOrphanedWaiting(QUEUE_ID)).isZero();
    }

    /**
     * 🔴 초안의 <b>알려진 한계였던 구간</b>이 이제 닫혔다.
     *
     * <p>{@code tokens} Hash가 통째로 유실되면 {@code admit.lua}가 뽑은 전원이 미스라 전부 되돌아가고
     * watermark가 <b>동결</b>된다. 위치 기반 판정은 그때 정확히 <b>0</b>을 보고했다 —
     * 큐가 영구 정지하는 가장 심각한 순간에 눈이 멀었다. Hash 미스로 판정하면 그대로 잡힌다.
     */
    @Test
    @DisplayName("tokens Hash 전손(watermark 동결 구간)도 잡는다 (U9)")
    void detectsMassOrphansWhenWatermarkFrozen() {
        for (int seq = 1; seq <= 200; seq++) {
            orphan("u" + seq, seq);
        }
        redis.opsForValue().set(WATERMARK, "0");   // 한 번도 못 올라간 상태

        assertThat(engine.countOrphanedWaiting(QUEUE_ID)).isEqualTo(200L);
    }

    @Test
    @DisplayName("빈 큐는 0 — Hash를 읽지 않는다 (U9)")
    void returnsZeroForEmptyQueue() {
        assertThat(engine.countOrphanedWaiting(QUEUE_ID)).isZero();
    }
}
