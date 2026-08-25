package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.ReclaimedToken;
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
 * {@code waitingTtl}(절대 만료) 회수 검증 — 실제 Redis, {@code waiting_expire.lua}.
 *
 * <p>🔑 <b>이 경로가 §82 구멍 ③의 마지노선이다.</b> enqueue만 하고 첫 폴링 전에 떠난 사람은
 * {@code last-active}에 멤버가 없어 {@code inactive_expire.lua}가 영영 못 본다
 * (2026-08-24 실서버로 재현됨). 여기가 틀리면 그 사람들이 큐에 영원히 남는다.
 *
 * <p>검증 명제 — 전부 조용히 깨지는 것들이다.
 * <ul>
 *   <li>판정 기준은 <b>발급 시각</b>이다. 폴링 이력({@code last-active})을 보지 않는다 —
 *       그걸 보면 {@code inactiveTtl}과 같아져 구멍 ③이 그대로 열린다</li>
 *   <li>회수는 <b>세 키 모두</b>에서 뺀다. {@code tokens}를 빠뜨리면 영구 락아웃,
 *       {@code last-active}를 빠뜨리면 stale 멤버가 inactive sweep의 한도를 매 주기 먹는다</li>
 *   <li>🔴 <b>고아({@code tokens} 미스)는 건드리지 않는다</b> — U9 gauge가 세는 대상이라
 *       여기서 조용히 치우면 탐지 수단이 무력화된다</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
@Tag("redis")
class WaitingExpireTest {

    private static final String QUEUE_ID = "q_test_waiting_ttl";
    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String LAST_ACTIVE = QueueKeys.lastActive(QUEUE_ID);

    /** 판정 기준 시각. 이 값보다 **이전**에 발급된 사람이 대상이다. */
    private static final long CUTOFF = 1_755_530_000_000L;
    private static final long OLD = CUTOFF - 60_000;   // 만료 대상
    private static final long NEW = CUTOFF + 60_000;   // 아직 아님

    private static final int LIMIT = 500;

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisQueueEngine engine;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 세 키 모두 {queueId} 해시태그라 같은 슬롯이다 — 다중 키 DEL이 Cluster에서도 성립한다.
        redis.delete(List.of(WAITING, TOKENS, LAST_ACTIVE));
    }

    /** 정상 대기자: waiting에 자리 + tokens에 게이트("tokenId|issuedAt"). */
    private void waiting(String identifier, long seq, long issuedAt) {
        redis.opsForZSet().add(WAITING, identifier, seq);
        redis.opsForHash().put(TOKENS, identifier, "tok_" + identifier + "|" + issuedAt);
    }

    @Test
    @DisplayName("발급 시각이 cutoff 이전인 대기자만 회수한다")
    void reclaimsOnlyThoseIssuedBeforeCutoff() {
        waiting("u1", 1, OLD);
        waiting("u2", 2, OLD);
        waiting("u3", 3, NEW);

        List<ReclaimedToken> claimed = engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT);

        assertThat(claimed).extracting(ReclaimedToken::identifier).containsExactly("u1", "u2");
        assertThat(claimed).extracting(ReclaimedToken::tokenId).containsExactly("tok_u1", "tok_u2");
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("u3");
    }

    /**
     * 🔴 <b>구멍 ③의 고정 테스트.</b> 폴링을 한 번도 안 한 사람({@code last-active}에 멤버가 없다)이
     * 회수되는지 본다. 이 경로가 그 사람을 잡는 <b>유일한</b> 수단이다.
     */
    @Test
    @DisplayName("폴링을 한 번도 안 한 사람도 회수한다 — §82 구멍 ③의 마지노선")
    void reclaimsEvenWithoutAnyPolling() {
        waiting("never_polled", 1, OLD);
        // last-active에 아무것도 넣지 않는다 = enqueue 후 첫 폴링 전 이탈

        List<ReclaimedToken> claimed = engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT);

        assertThat(claimed).extracting(ReclaimedToken::identifier).containsExactly("never_polled");
        assertThat(redis.opsForZSet().size(WAITING)).isZero();
    }

    /**
     * 🔴 폴링을 계속하고 있어도 회수된다. {@code inactiveTtl}과 갈리는 지점이다 —
     * 여기가 {@code last-active}를 보면 절대 만료가 성립하지 않는다.
     */
    @Test
    @DisplayName("폴링이 살아 있어도 절대 만료는 적용된다 — inactiveTtl과 갈리는 지점")
    void reclaimsEvenWhenPollingIsAlive() {
        waiting("still_polling", 1, OLD);
        redis.opsForZSet().add(LAST_ACTIVE, "1", CUTOFF + 300_000);   // 방금 폴링했다

        List<ReclaimedToken> claimed = engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT);

        assertThat(claimed).hasSize(1);
        assertThat(redis.opsForZSet().size(LAST_ACTIVE)).isZero();   // stale 멤버를 남기지 않는다
    }

    @Test
    @DisplayName("회수는 waiting·tokens·last-active 세 키 모두에서 뺀다")
    void removesFromAllThreeKeys() {
        waiting("u1", 7, OLD);
        redis.opsForZSet().add(LAST_ACTIVE, "7", OLD);

        engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT);

        assertThat(redis.opsForZSet().size(WAITING)).isZero();
        assertThat(redis.opsForHash().size(TOKENS)).isZero();
        assertThat(redis.opsForZSet().size(LAST_ACTIVE)).isZero();
    }

    /**
     * 🔴 고아를 건드리면 U9 gauge({@code queue_waiting_orphans})가 영원히 0이 되어 탐지 수단이
     * 사라진다. issuedAt을 모르므로 만료 판정 자체도 성립하지 않는다.
     */
    @Test
    @DisplayName("고아(tokens Hash 미스)는 건드리지 않는다 — U9 탐지 대상이다")
    void leavesOrphansAlone() {
        redis.opsForZSet().add(WAITING, "orphan", 1);   // tokens에 항목 없음
        waiting("u2", 2, OLD);

        List<ReclaimedToken> claimed = engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT);

        assertThat(claimed).extracting(ReclaimedToken::identifier).containsExactly("u2");
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("orphan");
        assertThat(engine.countOrphanedWaiting(QUEUE_ID)).isEqualTo(1L);
    }

    /**
     * 상한은 <b>검사할</b> 건수지 회수 건수가 아니다. 상한을 넘는 몫은 다음 주기가 가져간다 —
     * 만료 대상은 늘 앞(낮은 seq)에 모이므로 굶지 않는다.
     */
    @Test
    @DisplayName("limit은 검사 건수 상한이고, 남은 몫은 다음 호출이 가져간다")
    void limitBoundsScanAndRemainderIsTakenNextTime() {
        for (int seq = 1; seq <= 5; seq++) {
            waiting("u" + seq, seq, OLD);
        }

        assertThat(engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, 2))
                .extracting(ReclaimedToken::identifier).containsExactly("u1", "u2");
        assertThat(engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, 2))
                .extracting(ReclaimedToken::identifier).containsExactly("u3", "u4");
        assertThat(engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, 2))
                .extracting(ReclaimedToken::identifier).containsExactly("u5");
    }

    @Test
    @DisplayName("대상이 없으면 빈 목록 — 큐를 건드리지 않는다")
    void returnsEmptyWhenNothingExpired() {
        waiting("u1", 1, NEW);

        assertThat(engine.claimExpiredWaiting(QUEUE_ID, CUTOFF, LIMIT)).isEmpty();
        assertThat(redis.opsForZSet().size(WAITING)).isEqualTo(1L);
    }
}
