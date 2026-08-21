package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.ExpiredAdmit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * enqueue → admit → 재-enqueue를 <b>이어서</b> 도는 회귀 테스트 (실제 Redis Lua).
 *
 * <p><b>왜 이 테스트가 필요한가:</b> 기존 admit 테스트는 {@code tokens} Hash를 손으로 심거나
 * ({@code opsForHash().put}) 엔진을 mock해서, "admit이 waiting에서만 빼고 Hash는 남긴다"는
 * 사실이 enqueue의 중복 판정과 만나는 지점을 <b>아무도 실행하지 않았다</b>. 그래서
 * 중복 게이트가 {@code ZADD NX}(= waiting 존재)였을 때 admit된 사람의 재-enqueue가
 * <b>신규</b>로 판정되는 회귀가 통과했다. 그 결과는 폴링 404 · 과금 중복
 * ({@code billing_snapshots}는 {@code tokens} 행을 {@code COUNT}한다) · {@code status=1} 고아 행이다.
 *
 * <p>검증 명제 둘.
 * <ul>
 *   <li>admit된 사람이 다시 enqueue하면 <b>EXISTS + 같은 tokenId</b> (자리·과금이 늘지 않는다)</li>
 *   <li>{@code cleanupCompleted} 뒤에는 <b>OK + 새 tokenId</b> (게이트가 락아웃이 되지 않는다)</li>
 *   <li>🔴 <b>admitToken TTL 만료 뒤에도 OK + 새 tokenId</b> (§36). 만료 claim이 {@code HDEL}로
 *       게이트를 풀지 않으면 그 사람은 재-enqueue가 {@code EXISTS}(rank -1)로 갇혀
 *       <b>영원히 재입장하지 못한다</b> — 폐기 검토에서 이 경로가 결정적이었다</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class EnqueueAdmitReenqueueTest {

    private static final String QUEUE_ID = "q_dev_reenqueue";
    private static final String IDENTIFIER = "0190e2c1-user";
    private static final long NOW = 1_755_000_000_000L;

    @Autowired private RedisQueueEngine engine;
    @Autowired private BatchProcessor batchProcessor;
    @Autowired private StringRedisTemplate redis;

    /** admitToken은 UUIDv7이라 값을 모은 뒤에야 지울 수 있다. */
    private final List<String> issuedAdmitTokens = new ArrayList<>();

    private Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeEach
    void startConsumer() {
        cleanKeys();
        running.set(true);
        // enqueue는 Global Queue → BatchProcessor 경로라 drain해 줄 주체가 있어야 한다.
        consumerThread = new Thread(() -> {
            while (running.get()) {
                try {
                    batchProcessor.processBatches();
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    @AfterEach
    void stopConsumer() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        cleanKeys();
    }

    private void cleanKeys() {
        List<String> keys = new ArrayList<>(List.of(
                QueueKeys.waiting(QUEUE_ID), QueueKeys.seq(QUEUE_ID), QueueKeys.tokens(QUEUE_ID),
                QueueKeys.admitted(QUEUE_ID), QueueKeys.admitWatermark(QUEUE_ID),
                QueueKeys.lastActive(QUEUE_ID),
                QueueKeys.admitIdem(QUEUE_ID, "req-1")));
        issuedAdmitTokens.forEach(t -> keys.add(QueueKeys.admitByAdmit(QUEUE_ID, t)));
        redis.delete(keys);
        issuedAdmitTokens.clear();
    }

    /**
     * 🔴 §36 회귀. admitToken TTL이 만료된 사람은 대기열로 <b>돌아오지 않는다</b>. 대신 claim-Lua가
     * {@code tokens} Hash 필드를 지워 중복 게이트를 풀어, 그 사람이 <b>재접속 → 재-enqueue → 맨 뒤</b>로
     * 다시 설 수 있게 한다.
     *
     * <p><b>이 단언이 깨지면 유저가 영구 락아웃된다.</b> {@code HDEL}이 빠지면 {@code HSETNX}가 계속
     * 0을 돌려줘 재-enqueue가 {@code EXISTS} + rank −1을 받고, 그 tokenId로 폴링하면 404다.
     * identifier가 "같은 사용자·같은 큐에는 항상 같은 UUIDv7"이라 새 identifier로 우회할 수도 없다.
     */
    @Test
    @DisplayName("admitToken TTL 만료 뒤 재-enqueue는 OK + 새 tokenId — 복귀가 아니라 게이트 해제다 (§36)")
    void reenqueueAfterAdmitExpiryGetsFreshToken() {
        EnqueueResult first = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(first.isOk()).isTrue();
        String firstTokenId = first.getTokenId();
        long firstSeq = first.getSeq();

        AdmitResult admitted = engine.admit(QUEUE_ID, "req-1", 1, NOW);
        admitted.records().forEach(r -> issuedAdmitTokens.add(r.admitToken()));
        assertThat(admitted.records()).hasSize(1);

        // TTL 만료 — 배치가 claim한다. NOW보다 뒤 시각을 넘겨 만료 상태를 만든다.
        List<ExpiredAdmit> claimed = engine.claimExpiredAdmits(QUEUE_ID, NOW + 60_001, 500);
        assertThat(claimed).singleElement().satisfies(e -> {
            assertThat(e.identifier()).isEqualTo(IDENTIFIER);
            assertThat(e.seq()).isEqualTo(firstSeq);
            assertThat(e.tokenId()).as("issuedAt 원본과 함께 나와야 EXPIRED를 발행할 수 있다")
                    .isEqualTo(firstTokenId);
            assertThat(e.publishable()).isTrue();
        });

        // 🔴 대기열로 되돌아가지 않는다 — 되돌리면 좀비가 맨 앞으로 무한 재순환한다
        assertThat(redis.opsForZSet().score(QueueKeys.waiting(QUEUE_ID), IDENTIFIER)).isNull();
        // 🔴 게이트가 풀렸다
        assertThat(redis.opsForHash().hasKey(QueueKeys.tokens(QUEUE_ID), IDENTIFIER)).isFalse();

        // 재-enqueue = 신규다. 새 tokenId·새 seq를 받아 맨 뒤에 선다.
        EnqueueResult again = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(again.isOk()).as("EXISTS면 영구 락아웃이다").isTrue();
        assertThat(again.getTokenId()).isNotEqualTo(firstTokenId);
        assertThat(again.getSeq()).isGreaterThan(firstSeq);
        assertThat(redis.opsForZSet().score(QueueKeys.waiting(QUEUE_ID), IDENTIFIER)).isNotNull();
    }

    @Test
    @DisplayName("admit된 사람의 재-enqueue는 EXISTS + 같은 tokenId, complete 정리 뒤에는 OK + 새 tokenId")
    void admittedUserReenqueueIsNotNew() {
        EnqueueResult first = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(first.isOk()).isTrue();
        String tokenId = first.getTokenId();
        long seq = first.getSeq();

        AdmitResult admitted = engine.admit(QUEUE_ID, "req-1", 1, NOW);
        admitted.records().forEach(r -> issuedAdmitTokens.add(r.admitToken()));
        assertThat(admitted.records()).hasSize(1);
        assertThat(admitted.records().get(0).tokenId()).isEqualTo(tokenId);
        // admit은 waiting에서만 뺀다. tokens Hash는 남아 있고, 그것이 중복 게이트다.
        assertThat(redis.opsForZSet().score(QueueKeys.waiting(QUEUE_ID), IDENTIFIER)).isNull();
        assertThat(redis.opsForHash().hasKey(QueueKeys.tokens(QUEUE_ID), IDENTIFIER)).isTrue();

        // ① 재-enqueue → 신규가 아니다. 새 tokenId가 나오면 tokens 행이 하나 더 생겨 과금이 두 번 잡힌다.
        EnqueueResult again = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(again.isExists()).isTrue();
        assertThat(again.getTokenId()).isEqualTo(tokenId);
        // waiting에 없으므로 ZRANK·ZSCORE는 nil이다. -1로 방어하지 않으면 배열이 잘려
        // 청크(최대 500건)가 통째로 실패한다.
        assertThat(again.getRank()).isEqualTo(-1L);
        assertThat(again.getSeq()).isEqualTo(-1L);
        assertThat(again.getDisplayRank()).isEqualTo(-1L);
        // 대기열로 되살아나지도 않는다 (admit된 사람이 다시 줄에 서면 자리가 둘이 된다)
        assertThat(redis.opsForZSet().score(QueueKeys.waiting(QUEUE_ID), IDENTIFIER)).isNull();

        // ② complete 정리 뒤에는 다시 들어올 수 있어야 한다 (게이트가 영구 락아웃이면 안 된다)
        engine.cleanupCompleted(QUEUE_ID, IDENTIFIER, tokenId, admitted.records().get(0).admitToken(), seq);
        assertThat(redis.opsForHash().hasKey(QueueKeys.tokens(QUEUE_ID), IDENTIFIER)).isFalse();

        EnqueueResult rejoined = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(rejoined.isOk()).isTrue();
        assertThat(rejoined.getTokenId()).isNotEqualTo(tokenId);
        assertThat(rejoined.getSeq()).isGreaterThan(seq);
    }
}
