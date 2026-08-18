package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.ExpiredAdmit;
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
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * admitToken TTL 만료 → WAITING 복귀 claim 검증 (실제 Redis, {@code admit_expire.lua}).
 *
 * <p>검증 명제 — 전부 조용히 깨지는 것들이다.
 * <ul>
 *   <li>복귀는 <b>원래 seq 그대로</b>다. 새 seq를 받으면 60초 기다린 사람이 맨 뒤로 밀린다(§36)</li>
 *   <li>{@code identifier}에 {@code '|'}가 있어도 <b>첫 {@code '|'}로만</b> 쪼갠다.
 *       오른쪽 기준이면 identifier가 잘려 <b>다른 사람이 복귀한다</b></li>
 *   <li>{@code tokens} Hash 미스여도 대기열에는 되돌아간다 — 발행만 못 할 뿐 순번을 잃지 않는다</li>
 *   <li>🔴 <b>batch N대가 동시에 돌아도 복귀는 정확히 1회</b>다. {@code EVAL} 자체가 claim이라
 *       ShedLock이 필요 없다는 §80 ⑧의 근거를 실제로 재는 부분이다</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class AdmitExpiryReclaimTest {

    private static final String QUEUE_ID = "q_dev_admit_expiry";
    private static final long NOW = 1_755_530_000_000L;
    private static final long ISSUED_AT = 1_700_000_000_000L;

    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String ADMITTED = QueueKeys.admitted(QUEUE_ID);

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisQueueEngine engine;

    @Autowired @Qualifier("enqueueBulkScript") private RedisScript<List> enqueueBulkScript;
    @Autowired @Qualifier("pollVerifyScript") private RedisScript<Long> pollVerifyScript;
    @Autowired @Qualifier("admitScript") private RedisScript<List> admitScript;
    @Autowired @Qualifier("admitExpireScript") private RedisScript<List> admitExpireScript;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 세 키 모두 {queueId} 해시태그라 같은 슬롯이다 — 다중 키 DEL이 Cluster에서도 성립한다.
        redis.delete(List.of(WAITING, TOKENS, ADMITTED));
    }

    /** admit된 상태를 흉내낸다: waiting에는 없고, admitted에 만료 시각과 함께 들어 있다. */
    private void seedAdmitted(String identifier, long seq, long expiresAt) {
        redis.opsForZSet().add(ADMITTED, seq + "|" + identifier, expiresAt);
        redis.opsForHash().put(TOKENS, identifier, "tok_" + seq + "|" + ISSUED_AT);
    }

    @Test
    @DisplayName("만료분만 원래 seq로 WAITING 복귀하고 admitted에서 빠진다 (미만료는 그대로)")
    void expiredOnesReturnToWaitingWithOriginalSeq() {
        seedAdmitted("id-a", 7, NOW - 1);      // 만료
        seedAdmitted("id-b", 9, NOW);          // 경계 — score <= now 이므로 만료다
        seedAdmitted("id-c", 11, NOW + 1);     // 아직 유효

        List<ExpiredAdmit> claimed = engine.claimExpiredAdmits(QUEUE_ID, NOW, 500);

        assertThat(claimed).containsExactly(
                new ExpiredAdmit("id-a", 7, "tok_7", Instant.ofEpochMilli(ISSUED_AT)),
                new ExpiredAdmit("id-b", 9, "tok_9", Instant.ofEpochMilli(ISSUED_AT)));

        // 🔴 우선순위 보존 — 새 seq가 아니라 원래 seq여야 한다
        assertThat(redis.opsForZSet().score(WAITING, "id-a")).isEqualTo(7.0);
        assertThat(redis.opsForZSet().score(WAITING, "id-b")).isEqualTo(9.0);
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("id-a", "id-b");

        // 유효한 것은 건드리지 않는다
        assertThat(redis.opsForZSet().range(ADMITTED, 0, -1)).containsExactly("11|id-c");
    }

    @Test
    @DisplayName("identifier에 '|'가 있어도 첫 '|'로만 쪼갠다 (오른쪽 기준이면 다른 사람이 복귀한다)")
    void splitsOnFirstPipeOnly() {
        String identifier = "user|42|kr";
        seedAdmitted(identifier, 3, NOW - 1);

        List<ExpiredAdmit> claimed = engine.claimExpiredAdmits(QUEUE_ID, NOW, 500);

        assertThat(claimed).singleElement()
                .extracting(ExpiredAdmit::identifier, ExpiredAdmit::seq)
                .containsExactly(identifier, 3L);
        assertThat(redis.opsForZSet().score(WAITING, identifier)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("tokens Hash 미스여도 대기열에는 복귀한다 — tokenId는 null(발행 불가)")
    void hashMissStillReturnsToWaiting() {
        redis.opsForZSet().add(ADMITTED, "5|id-ghost", NOW - 1);   // tokens Hash 항목 없음

        List<ExpiredAdmit> claimed = engine.claimExpiredAdmits(QUEUE_ID, NOW, 500);

        assertThat(claimed).singleElement().satisfies(e -> {
            assertThat(e.identifier()).isEqualTo("id-ghost");
            assertThat(e.tokenId()).isNull();
            assertThat(e.issuedAt()).isNull();
            assertThat(e.publishable()).isFalse();
        });
        assertThat(redis.opsForZSet().score(WAITING, "id-ghost")).isEqualTo(5.0);
        assertThat(redis.opsForZSet().zCard(ADMITTED)).isZero();
    }

    @Test
    @DisplayName("만료분이 없으면 빈 목록 — 아무것도 건드리지 않는다")
    void nothingExpiredReturnsEmpty() {
        seedAdmitted("id-a", 1, NOW + 1000);

        assertThat(engine.claimExpiredAdmits(QUEUE_ID, NOW, 500)).isEmpty();
        assertThat(redis.opsForZSet().zCard(WAITING)).isZero();
        assertThat(redis.opsForZSet().zCard(ADMITTED)).isEqualTo(1);
    }

    /**
     * 🔴 batch 3대 동시 기동 — 복귀는 정확히 1회.
     *
     * <p>{@code ZRANGEBYSCORE} + {@code ZREM}이 한 Lua라 {@code EVAL} 자체가 claim이다.
     * 세 인스턴스가 같은 순간에 깨어나도 멤버를 가져가는 것은 한 대뿐이고 나머지는 빈 배열을
     * 받는다 — 그래서 ShedLock·leader election이 없다(§80 ⑧). <b>이 단언이 그 근거다.</b>
     *
     * <p>limit을 작게(10) 잡아 세 인스턴스가 실제로 여러 번 경쟁하게 만든다. 한 번에 다 집어가면
     * "먼저 온 놈이 다 먹었다"만 보고 경쟁 구간을 재지 못한다.
     *
     * <p>스레드는 <b>가상 스레드</b>다. 고정 크기 풀은 출발 신호에서 교착한다({@code CLAUDE.md}).
     */
    @Test
    @DisplayName("batch 3대가 동시에 돌아도 복귀는 정확히 1회 — EVAL 자체가 claim이다")
    void threeInstancesReclaimEachMemberExactlyOnce() throws Exception {
        int total = 300;
        for (int i = 1; i <= total; i++) {
            seedAdmitted("id-" + i, i, NOW - 1);
        }

        // 인스턴스 3대. 같은 Redis를 보지만 서로의 존재를 모른다 — 조율 수단은 EVAL뿐이다.
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
                        List<ExpiredAdmit> claimed = instance.claimExpiredAdmits(QUEUE_ID, NOW, 10);
                        if (claimed.isEmpty()) {
                            return null;   // 다른 인스턴스가 다 가져갔거나 비었다
                        }
                        perInstance.addAndGet(slot, claimed.size());
                        claimed.forEach(e -> allClaimed.add(e.identifier()));
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }   // close()가 종료를 기다린다

        // 분배는 단언하지 않는다 — 스케줄링에 달려 있어 단언하면 플래키해진다.
        // 대신 실제로 경쟁이 일어났는지 눈으로 확인할 수 있게 남긴다.
        System.out.println("인스턴스별 claim 건수 = " + perInstance);

        // 중복 0건 · 결번 0건
        assertThat(allClaimed).hasSize(total);
        assertThat(Set.copyOf(allClaimed)).hasSize(total);

        assertThat(redis.opsForZSet().zCard(WAITING)).isEqualTo(total);
        assertThat(redis.opsForZSet().zCard(ADMITTED)).isZero();
        assertThat(redis.opsForZSet().score(WAITING, "id-1")).isEqualTo(1.0);
        assertThat(redis.opsForZSet().score(WAITING, "id-" + total)).isEqualTo((double) total);
    }

    /** 별도 인스턴스(= 다른 서버의 queue-batch)를 흉내낸다. 공유하는 것은 Redis뿐이다. */
    private RedisQueueEngine newInstance() {
        return new RedisQueueEngine(redis, enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript);
    }
}
