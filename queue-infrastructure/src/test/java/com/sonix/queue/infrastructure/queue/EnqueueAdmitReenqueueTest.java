package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueResult;
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
