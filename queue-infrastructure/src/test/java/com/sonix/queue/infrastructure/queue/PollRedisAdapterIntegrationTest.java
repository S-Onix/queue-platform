package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.QueueSnapshot;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 폴링 어댑터 통합 테스트 (실제 Redis).
 *
 * <p><b>대기자 1만명 규모</b>로 waiting ZSet + tokens Hash를 seed하고, 폴링이 쓰는 두 연산
 * ({@code readSnapshot} / {@code verifyWaiting})을 검증한다.
 * enqueue 경로를 우회하고 직접 seed하므로 tenant/DB가 필요 없다
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
    private static final String TOKENS_KEY = QueueKeys.tokens(QUEUE_ID);
    private static final int WAITERS = 10_000;
    private static final long NOW = 1_700_000_000_000L;

    /** seq i의 소유자 토큰 (enqueue가 넣는 "tokenId|issuedAt" 포맷 그대로). */
    private static String tokenOf(int i) {
        return "tok_" + i;
    }

    /**
     * 대기자 1만명: waiting은 member=user_i, score=seq=i (seq 1..10000).
     * tokens Hash에는 identifier -> "tokenId|issuedAt"을 같이 넣는다 — verifyWaiting이 이 값을 대조한다.
     */
    @BeforeEach
    void seed() {
        redisTemplate.delete(WAITING_KEY);
        redisTemplate.delete(LAST_ACTIVE_KEY);
        redisTemplate.delete(TOKENS_KEY);

        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>(WAITERS * 2);
        Map<String, String> tokens = new HashMap<>(WAITERS * 2);
        for (int i = 1; i <= WAITERS; i++) {
            tuples.add(new DefaultTypedTuple<>("user_" + i, (double) i));
            tokens.put("user_" + i, tokenOf(i) + "|" + NOW);
        }
        redisTemplate.opsForZSet().add(WAITING_KEY, tuples);
        redisTemplate.opsForHash().putAll(TOKENS_KEY, tokens);
    }

    @AfterEach
    void cleanup() {
        redisTemplate.delete(WAITING_KEY);
        redisTemplate.delete(LAST_ACTIVE_KEY);
        redisTemplate.delete(TOKENS_KEY);
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
    @DisplayName("verifyWaiting: seq와 tokenId가 모두 맞으면 true")
    void verifyWaiting_match() {
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 1, tokenOf(1), false, NOW)).isTrue();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), false, NOW)).isTrue();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, WAITERS, tokenOf(WAITERS), false, NOW)).isTrue();
    }

    @Test
    @DisplayName("verifyWaiting: 없는 seq면 false")
    void verifyWaiting_absentSeq() {
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, WAITERS + 1, tokenOf(WAITERS + 1), false, NOW)).isFalse();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 0, tokenOf(0), false, NOW)).isFalse();
    }

    @Test
    @DisplayName("verifyWaiting: tokenId가 다르면 false이고 lastActive도 갱신되지 않는다 (남의 TTL 연장 차단)")
    void verifyWaiting_tokenMismatch_doesNotTouch() {
        // seq는 큐별 INCR이라 추측이 자명하다. 남의 seq에 ka=1을 걸어도 아무 일도 없어야 한다.
        boolean ok = queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(4999), true, NOW);

        assertThat(ok).isFalse();
        assertThat(redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, "5000")).isNull();
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("verifyWaiting: 빈/null tokenId도 false, lastActive 미갱신")
    void verifyWaiting_blankToken() {
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, null, true, NOW)).isFalse();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, "", true, NOW)).isFalse();

        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("verifyWaiting: keepalive=true & 검증 통과 시 last-active에 seq가 score=now로 기록")
    void verifyWaiting_keepaliveRecordsTimestamp() {
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), true, NOW)).isTrue();

        Double score = redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, "5000");
        assertThat(score).isNotNull();
        assertThat(score.longValue()).isEqualTo(NOW);
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isEqualTo(1L);
    }

    @Test
    @DisplayName("verifyWaiting: keepalive=false면 검증만 하고 last-active는 남기지 않는다")
    void verifyWaiting_noKeepalive() {
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), false, NOW)).isTrue();

        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("verifyWaiting: 재호출 시 같은 seq의 score(now)만 갱신, 건수 그대로")
    void verifyWaiting_keepaliveUpdatesScore() {
        queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), true, 1000L);
        queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), true, 2000L);

        Double score = redisTemplate.opsForZSet().score(LAST_ACTIVE_KEY, "5000");
        assertThat(score.longValue()).isEqualTo(2000L);
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isEqualTo(1L);
    }

    @Test
    @DisplayName("verifyWaiting: 구분자 없는 구식 Hash 값(tokenId만)도 대조된다")
    void verifyWaiting_legacyTokenValue() {
        redisTemplate.opsForHash().put(TOKENS_KEY, "user_5000", tokenOf(5000));   // issuedAt 없음

        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), false, NOW)).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 악의적 사용자 전제 시나리오
    //
    // 폴링은 public(인증 없음) + Redis-only 경로다. seq는 큐별 INCR이라 "1부터 훑기"가
    // 자명하고, tokenId만이 유일한 소유권 증명이다. 아래는 전부 "남의 대기열 상태를 읽거나
    // 남의 TTL을 연장하려는" 시도이며, 모두 false + last-active 무변동이어야 한다.
    // last-active가 오염되면 배치의 inactive_ttl 판정이 통째로 틀어진다.
    // ────────────────────────────────────────────────────────────────────────

    /** 큐 교차 테스트용 두 번째 큐. 테스트 큐는 q_test_* prefix를 쓰고 @AfterEach에서 지운다. */
    private static final String OTHER_QUEUE_ID = "q_test_poll_other";

    @AfterEach
    void cleanupOtherQueue() {
        redisTemplate.delete(QueueKeys.waiting(OTHER_QUEUE_ID));
        redisTemplate.delete(QueueKeys.tokens(OTHER_QUEUE_ID));
        redisTemplate.delete(QueueKeys.lastActive(OTHER_QUEUE_ID));
    }

    @Test
    @DisplayName("공격: 남의 seq + 내 tokenId(교차 탈취) → false, last-active에 아무것도 남지 않는다")
    void attack_crossOwnership() {
        // 공격자는 자기 tokenId(tok_10)는 정당하게 갖고 있고, 피해자 seq(5000)만 바꿔 넣는다.
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(10), true, NOW)).isFalse();
        // 반대 방향(피해자 seq는 맞고 tokenId가 남의 것)도 동일
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 10, tokenOf(5000), true, NOW)).isFalse();

        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("공격: 남의 seq에 ka=true 100회 연타해도 last-active는 끝까지 비어 있다 (TTL 연장 차단)")
    void attack_keepaliveFlood() {
        for (int i = 0; i < 100; i++) {
            assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(10), true, NOW + i)).isFalse();
        }
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("공격: 자기 tokenId로 seq 1~50 열거 스캔 → 전부 false, last-active 무변동")
    void attack_seqEnumeration() {
        String attackerToken = tokenOf(7777);   // 공격자 본인 토큰 (seq 7777 소유)
        for (long seq = 1; seq <= 50; seq++) {
            assertThat(queueEngine.verifyWaiting(QUEUE_ID, seq, attackerToken, true, NOW))
                    .as("seq=%d", seq).isFalse();
        }
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("이상값 seq(0/-1/Long.MAX/Long.MIN)를 넣어도 예외 없이 false")
    void attack_boundarySeq() {
        // Lua의 tonumber는 Long.MIN/MAX를 double로 받으므로 정밀도가 깨진다.
        // ZRANGEBYSCORE가 매치를 못 하는 것으로 끝나야 하고, 스크립트 에러가 나면 안 된다.
        for (long seq : new long[]{0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThatCode(() ->
                    assertThat(queueEngine.verifyWaiting(QUEUE_ID, seq, tokenOf(1), true, NOW))
                            .as("seq=%d", seq).isFalse()
            ).doesNotThrowAnyException();
        }
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("공격: 파이프 주입 tokenId('tok_5000|999')로 splitTokenValue 규칙을 노려도 뚫리지 않는다")
    void attack_pipeInjectionInTokenId() {
        // Lua는 저장값 "tokenId|issuedAt"을 '|'로 잘라 앞부분만 비교한다.
        // 공격자가 '|'를 끼워 넣어 비교 규칙을 흉내내도, 자르는 대상은 '저장값'이지 '입력값'이 아니다.
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000) + "|" + NOW, true, NOW)).isFalse();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000) + "|999", true, NOW)).isFalse();
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, "|" + tokenOf(5000), true, NOW)).isFalse();

        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("이상한 tokenId(null/빈/공백/10KB/유니코드/glob/개행)를 밀어넣어도 예외 없이 false")
    void attack_malformedTokenIds() {
        String[] payloads = {
                null,
                "",
                "    ",
                "\t\n",
                "*",                                  // Redis glob — HGET은 패턴을 안 쓴다
                "user_*",
                "tok_5000\n",                         // 개행 (프로토콜 주입 시도)
                "tok_5000\r\nHGETALL",                // RESP 주입 시도 — Lua ARGV라 통하지 않음
                "토큰_5000",                           // 유니코드
                "tok_5000🔥",               // 이모지
                "'; return 1 --",                     // Lua 조각 주입 (ARGV는 코드가 아니라 데이터)
                "tok_5000\0",                     // NUL 바이트
                "x".repeat(10_240)                    // 10KB 초장문
        };

        for (String payload : payloads) {
            String label = (payload == null) ? "null" : "len=" + payload.length();
            assertThatCode(() ->
                    assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, payload, true, NOW))
                            .as("payload %s", label).isFalse()
            ).as("payload %s", label).doesNotThrowAnyException();
        }

        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("공격: 큐 A의 정당한 (seq, tokenId) 쌍을 큐 B에 제시하면 false (큐 경계 유지)")
    void attack_crossQueue() {
        // 큐 B에도 같은 seq가 존재하되 주인이 다른 상황 — "seq가 있으니 통과"가 되면 뚫린다.
        redisTemplate.opsForZSet().add(QueueKeys.waiting(OTHER_QUEUE_ID), "other_5000", 5000d);
        redisTemplate.opsForHash().put(QueueKeys.tokens(OTHER_QUEUE_ID), "other_5000", "tok_other_5000|" + NOW);

        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), false, NOW)).isTrue();      // A에선 유효
        assertThat(queueEngine.verifyWaiting(OTHER_QUEUE_ID, 5000, tokenOf(5000), true, NOW)).isFalse(); // B에선 무효

        assertThat(redisTemplate.opsForZSet().size(QueueKeys.lastActive(OTHER_QUEUE_ID))).isZero();
    }

    @Test
    @DisplayName("tokens Hash만 유실된 상태(waiting에는 있음)에서는 통과가 아니라 거부한다 (fail-closed)")
    void attack_tokensHashMissing_failsClosed() {
        // "못 찾으면 통과" 분기가 있으면 여기서 true가 난다. 소유권 근거가 사라졌으면 거부가 맞다.
        redisTemplate.opsForHash().delete(TOKENS_KEY, "user_5000");

        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), true, NOW)).isFalse();
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();

        // Hash 전체가 날아가도 동일
        redisTemplate.delete(TOKENS_KEY);
        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 1, tokenOf(1), true, NOW)).isFalse();
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }

    @Test
    @DisplayName("admit으로 waiting에서 빠진 뒤에는 tokens에 값이 남아있어도 keepalive가 되살아나지 않는다 (좀비 방지)")
    void attack_admittedEntry_cannotResurrectLastActive() {
        // 검증(ZRANGEBYSCORE)과 갱신(ZADD)이 한 스크립트가 아니면 이 틈에 좀비 last-active가 생긴다.
        redisTemplate.opsForZSet().remove(WAITING_KEY, "user_5000");   // admit/이탈

        assertThat(queueEngine.verifyWaiting(QUEUE_ID, 5000, tokenOf(5000), true, NOW)).isFalse();
        assertThat(redisTemplate.opsForZSet().size(LAST_ACTIVE_KEY)).isZero();
    }
}
