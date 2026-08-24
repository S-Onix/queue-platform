package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.ReclaimedToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code inactiveTtl} 초과 대기자 회수 검증 (실제 Redis, {@code inactive_expire.lua}).
 *
 * <p>🔴 <b>§82가 Cancel API를 폐기해 이것이 이탈 회수의 유일한 경로가 됐다.</b>
 * 여기가 틀리면 이탈자를 치울 방법이 아예 없다.
 *
 * <p>검증 명제 — 전부 조용히 깨지는 것들이다.
 * <ul>
 *   <li>회수는 <b>세 키 모두</b>에서 뺀다. {@code tokens} Hash를 빠뜨리면 그 사람은 재-enqueue가
 *       {@code EXISTS}로 갇혀 <b>영구 락아웃</b>이다</li>
 *   <li>{@code HGET}이 {@code HDEL}보다 <b>먼저</b>다. 순서가 뒤집히면 {@code issuedAt} 원본을
 *       못 읽어 같은 토큰의 두 번째 행이 생기고 한 건 더 청구된다(§82·§83)</li>
 *   <li>🔴 <b>{@code waiting}에 없는 seq는 건드리지 않는다</b>(§36 역산 미스 규약). 그 사람은
 *       admit되어 큐 밖이다 — 지우면 admit 대기자의 중복 게이트가 풀려 재-enqueue가 새 자리를
 *       받고 원래 자리는 유령이 된다. 다만 {@code last-active}에서는 빼야 다음 주기의 한도를
 *       먹지 않는다</li>
 *   <li>🔴 <b>batch N대가 동시에 돌아도 회수는 정확히 1회</b>다. {@code EVAL} 자체가 claim이라
 *       ShedLock이 필요 없다는 §80 ⑧의 근거를 실제로 재는 부분이다</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class InactiveReclaimTest {

    private static final String QUEUE_ID = "q_test_inactive";
    /** 컷오프. 이 값보다 **이전**에 마지막 폴링한 사람이 대상이다 (= now - inactiveTtl*1000). */
    private static final long CUTOFF = 1_755_530_000_000L;
    private static final long ISSUED_AT = 1_700_000_000_000L;

    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String LAST_ACTIVE = QueueKeys.lastActive(QUEUE_ID);

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisQueueEngine engine;

    @Autowired @Qualifier("enqueueBulkScript") private RedisScript<List> enqueueBulkScript;
    @Autowired @Qualifier("pollVerifyScript") private RedisScript<Long> pollVerifyScript;
    @Autowired @Qualifier("admitScript") private RedisScript<List> admitScript;
    @Autowired @Qualifier("admitExpireScript") private RedisScript<List> admitExpireScript;
    @Autowired @Qualifier("inactiveExpireScript") private RedisScript<List> inactiveExpireScript;

    @Autowired @Qualifier("waitingExpireScript") private RedisScript<List> waitingExpireScript;
    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 세 키 모두 {queueId} 해시태그라 같은 슬롯이다 — 다중 키 DEL이 Cluster에서도 성립한다.
        redis.delete(List.of(WAITING, TOKENS, LAST_ACTIVE));
    }

    /** 대기 중인 사람: waiting에 자리, tokens에 게이트, last-active에 마지막 폴링 시각. */
    private void seedWaiting(String identifier, long seq, long lastPolledAt) {
        redis.opsForZSet().add(WAITING, identifier, seq);
        redis.opsForHash().put(TOKENS, identifier, "tok_" + seq + "|" + ISSUED_AT);
        redis.opsForZSet().add(LAST_ACTIVE, String.valueOf(seq), lastPolledAt);
    }

    @Test
    @DisplayName("컷오프 이전에 폴링한 사람만 세 키에서 모두 빠진다 (최근 폴링자는 그대로)")
    void reclaimsOnlyStaleOnesFromAllThreeKeys() {
        seedWaiting("id-a", 7, CUTOFF - 1);       // 회수 대상
        seedWaiting("id-b", 9, CUTOFF - 60_000);  // 더 오래됨 — 대상
        seedWaiting("id-c", 11, CUTOFF);          // 경계 — '(cutoff 이므로 **대상 아님**
        seedWaiting("id-d", 13, CUTOFF + 1);      // 방금 폴링 — 대상 아님

        List<ReclaimedToken> claimed = engine.claimInactive(QUEUE_ID, CUTOFF, 500);

        assertThat(claimed).containsExactly(
                new ReclaimedToken("id-b", 9, "tok_9", Instant.ofEpochMilli(ISSUED_AT)),
                new ReclaimedToken("id-a", 7, "tok_7", Instant.ofEpochMilli(ISSUED_AT)));

        // 세 키 전부에서 빠졌다 — tokens를 빠뜨리면 영구 락아웃이다
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactlyInAnyOrder("id-c", "id-d");
        assertThat(redis.opsForHash().keys(TOKENS)).containsExactlyInAnyOrder("id-c", "id-d");
        assertThat(redis.opsForZSet().range(LAST_ACTIVE, 0, -1)).containsExactlyInAnyOrder("11", "13");
    }

    /**
     * 🔴 §36 역산 미스 규약. admit된 사람은 {@code ZPOPMIN}으로 {@code waiting}에서 빠졌지만
     * {@code last-active}에는 남아 있고, 그 값은 admit 대기 중 얼어붙는다.
     *
     * <p><b>그 사람을 건드리면 안 된다</b> — {@code tokens} 필드를 지우면 중복 게이트가 풀려
     * 재-enqueue가 새 자리를 받고, 원래 자리는 {@code admitted}에 남아 유령이 된다.
     * 반대로 {@code last-active}에서도 안 빼면 그 stale 멤버가 <b>매 주기 한도의 앞자리를 먹어</b>
     * 진짜 회수 대상을 굶긴다. 그래서 <b>last-active만 빼고 넘어간다.</b>
     */
    @Test
    @DisplayName("waiting에 없는 seq(=admit 대기자)는 tokens를 건드리지 않는다 — last-active만 뺀다 (§36)")
    void skipsSeqNotInWaitingButStillDropsFromLastActive() {
        // admit된 상태: waiting에는 없고 tokens·last-active에는 남아 있다
        redis.opsForHash().put(TOKENS, "id-admitted", "tok_5|" + ISSUED_AT);
        redis.opsForZSet().add(LAST_ACTIVE, "5", CUTOFF - 1);
        seedWaiting("id-waiting", 7, CUTOFF - 1);   // 정상 회수 대상

        List<ReclaimedToken> claimed = engine.claimInactive(QUEUE_ID, CUTOFF, 500);

        assertThat(claimed).singleElement()
                .extracting(ReclaimedToken::identifier).isEqualTo("id-waiting");

        assertThat(redis.opsForHash().hasKey(TOKENS, "id-admitted"))
                .as("admit 대기자의 게이트를 풀면 재-enqueue가 새 자리를 받아 유령이 생긴다")
                .isTrue();
        assertThat(redis.opsForZSet().score(LAST_ACTIVE, "5"))
                .as("그래도 last-active에서는 빼야 다음 주기의 한도를 먹지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("tokens Hash 미스여도 waiting·last-active에서는 빠진다 — tokenId는 null(발행 불가)")
    void hashMissStillRemovesFromQueue() {
        redis.opsForZSet().add(WAITING, "id-ghost", 3);
        redis.opsForZSet().add(LAST_ACTIVE, "3", CUTOFF - 1);   // tokens 항목 없음

        List<ReclaimedToken> claimed = engine.claimInactive(QUEUE_ID, CUTOFF, 500);

        assertThat(claimed).singleElement().satisfies(t -> {
            assertThat(t.identifier()).isEqualTo("id-ghost");
            assertThat(t.tokenId()).isNull();
            assertThat(t.issuedAt()).isNull();
            assertThat(t.publishable()).isFalse();
        });
        assertThat(redis.opsForZSet().zCard(WAITING)).isZero();
        assertThat(redis.opsForZSet().zCard(LAST_ACTIVE)).isZero();
    }

    @Test
    @DisplayName("identifier에 '|'가 있어도 온전히 다룬다 — tokens 값만 첫 '|'로 쪼갠다")
    void identifierWithPipeIsHandledWhole() {
        String identifier = "user|42|kr";
        seedWaiting(identifier, 3, CUTOFF - 1);

        List<ReclaimedToken> claimed = engine.claimInactive(QUEUE_ID, CUTOFF, 500);

        assertThat(claimed).singleElement()
                .extracting(ReclaimedToken::identifier, ReclaimedToken::tokenId)
                .containsExactly(identifier, "tok_3");
        assertThat(redis.opsForHash().hasKey(TOKENS, identifier)).isFalse();
    }

    @Test
    @DisplayName("대상이 없으면 빈 목록 — 아무것도 건드리지 않는다")
    void nothingStaleReturnsEmpty() {
        seedWaiting("id-a", 1, CUTOFF + 1000);

        assertThat(engine.claimInactive(QUEUE_ID, CUTOFF, 500)).isEmpty();
        assertThat(redis.opsForZSet().zCard(WAITING)).isEqualTo(1);
        assertThat(redis.opsForHash().size(TOKENS)).isEqualTo(1);
        assertThat(redis.opsForZSet().zCard(LAST_ACTIVE)).isEqualTo(1);
    }

    /**
     * 🔴 batch 3대 동시 기동 — 회수는 정확히 1회.
     *
     * <p>{@code ZRANGEBYSCORE} + {@code ZREM}이 한 Lua라 {@code EVAL} 자체가 claim이다.
     * 세 인스턴스가 같은 순간에 깨어나도 멤버를 가져가는 것은 한 대뿐이다 — 그래서 ShedLock이
     * 없다(§80 ⑧). <b>이 단언이 그 근거다.</b>
     *
     * <p>스레드는 <b>가상 스레드</b>다. 고정 크기 풀은 출발 신호에서 교착한다({@code CLAUDE.md}).
     */
    @Test
    @DisplayName("batch 3대가 동시에 돌아도 회수는 정확히 1회 — EVAL 자체가 claim이다")
    void threeInstancesReclaimEachMemberExactlyOnce() throws Exception {
        int total = 300;
        for (int i = 1; i <= total; i++) {
            seedWaiting("id-" + i, i, CUTOFF - 1);
        }

        List<RedisQueueEngine> instances = List.of(newInstance(), newInstance(), newInstance());
        List<String> allClaimed = Collections.synchronizedList(new ArrayList<>());
        AtomicIntegerArray perInstance = new AtomicIntegerArray(instances.size());
        CountDownLatch ready = new CountDownLatch(instances.size());
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < instances.size(); i++) {
                RedisQueueEngine instance = instances.get(i);
                int slot = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    while (true) {
                        // limit을 작게 잡아 세 인스턴스가 실제로 여러 번 경쟁하게 만든다.
                        List<ReclaimedToken> claimed = instance.claimInactive(QUEUE_ID, CUTOFF, 10);
                        if (claimed.isEmpty()) {
                            return null;
                        }
                        perInstance.addAndGet(slot, claimed.size());
                        claimed.forEach(t -> allClaimed.add(t.identifier()));
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }   // close()가 종료를 기다린다

        // 분배는 단언하지 않는다 — 스케줄링에 달려 있어 단언하면 플래키해진다.
        System.out.println("인스턴스별 claim 건수 = " + perInstance);

        assertThat(allClaimed).as("결번 0건").hasSize(total);
        assertThat(Set.copyOf(allClaimed)).as("중복 0건").hasSize(total);
        assertThat(redis.opsForZSet().zCard(WAITING)).isZero();
        assertThat(redis.opsForZSet().zCard(LAST_ACTIVE)).isZero();
        assertThat(redis.opsForHash().size(TOKENS))
                .as("전원의 중복 게이트가 풀렸다 — 하나라도 남으면 그 사람은 영구 락아웃이다")
                .isZero();
    }

    /** 별도 인스턴스(= 다른 서버의 queue-batch)를 흉내낸다. 공유하는 것은 Redis뿐이다. */
    private RedisQueueEngine newInstance() {
        return new RedisQueueEngine(redis, enqueueBulkScript, pollVerifyScript,
                admitScript, admitExpireScript, inactiveExpireScript, waitingExpireScript);
    }
}
