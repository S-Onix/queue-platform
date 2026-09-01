package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.ReclaimedToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
@Tag("redis")
class EnqueueAdmitReenqueueTest {

    private static final String QUEUE_ID = "q_dev_reenqueue";
    private static final String IDENTIFIER = "0190e2c1-user";
    private static final long NOW = 1_755_000_000_000L;

    @Autowired private RedisQueueEngine engine;
    @Autowired private BatchProcessor batchProcessor;
    @Autowired private StringRedisTemplate redis;

    /** admitToken은 UUIDv7이라 값을 모은 뒤에야 지울 수 있다. */
    private final List<String> issuedAdmitTokens = new ArrayList<>();
    /** admit-by-token도 마찬가지다 — 키 뒷조각이 tokenId라 발급분을 모아야 지운다. */
    private final List<String> issuedTokenIds = new ArrayList<>();

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
        issuedTokenIds.forEach(t -> keys.add(QueueKeys.admitByToken(QUEUE_ID, t)));
        redis.delete(keys);
        issuedAdmitTokens.clear();
        issuedTokenIds.clear();
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
        List<ReclaimedToken> claimed = engine.claimExpiredAdmits(QUEUE_ID, NOW + 60_001, 500);
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

    /**
     * 🔴 <b>늦은 complete가 재진입 사용자를 축출한다</b>는 회귀.
     *
     * <p>{@code identifier}는 회차 간에 <b>재사용</b>되는 값이다(같은 사람 = 같은 UUIDv7).
     * 그런데 {@code cleanupCompleted}는 {@code waiting}·{@code tokens}를 <b>identifier만 보고</b>
     * 지운다. §36이 admitToken TTL 만료 시 {@code HDEL}로 게이트를 풀어주므로, 만료된 사람은
     * 곧바로 재-enqueue해 <b>새 회차(gen2)</b>를 받는다. 그 상태에서 gen1의 complete가
     * {@code Token#COMPLETE_VALID_WINDOW_SECONDS}(300초) 안에 뒤늦게 도착하면 — admitToken TTL은
     * 60초라 <b>240초짜리 취약 창</b>이다 — gen1의 정리가 <b>gen2의 자리와 게이트를 지운다.</b>
     *
     * <p>피해자는 이미 한 번 만료로 손해 본 사람이고, 두 번째로 자리를 잃는다. 그것도
     * 폴링이 조용히 404가 될 뿐 아무 신호가 없다. {@code tokens} 행은 남으므로 과금은 그대로다.
     *
     * <p><b>왜 기존 두 테스트가 못 잡았나:</b> 둘 다 {@code cleanupCompleted}를 재-enqueue보다
     * <b>먼저</b> 부른다({@code admittedUserReenqueueIsNotNew}는 gen2 생성 전에, {@code
     * reenqueueAfterAdmitExpiryGetsFreshToken}은 gen2를 만들고 그냥 끝난다). "gen2가 살아 있는
     * 상태에서 gen1의 늦은 complete"라는 인터리빙을 <b>아무도 실행한 적이 없다.</b>
     *
     * <p>단언 4·5는 <b>과잉 가드 방지</b>다. "회차가 다르면 아무것도 안 지운다"로 잘못 넓히면
     * gen1의 {@code admit-by-admit}이 남아 verify가 최대 60초 계속 통과한다.
     */
    @Test
    @DisplayName("늦은 complete는 재-enqueue한 다음 회차를 축출하지 않는다 — 정리는 자기 회차만")
    void lateCompleteOfExpiredGenerationDoesNotEvictReenqueuedUser() {
        // gen1 — enqueue → admit
        EnqueueResult gen1 = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(gen1.isOk()).isTrue();
        String tokenId1 = gen1.getTokenId();
        long seq1 = gen1.getSeq();
        issuedTokenIds.add(tokenId1);

        AdmitResult admitted = engine.admit(QUEUE_ID, "req-1", 1, NOW);
        admitted.records().forEach(r -> issuedAdmitTokens.add(r.admitToken()));
        assertThat(admitted.records()).hasSize(1);
        String admitToken1 = admitted.records().get(0).admitToken();

        // admitToken TTL 만료 → §36이 게이트를 푼다 (복귀는 하지 않는다)
        assertThat(engine.claimExpiredAdmits(QUEUE_ID, NOW + 60_001, 500)).hasSize(1);

        // gen2 — 같은 identifier로 재접속. 새 tokenId·새 seq로 맨 뒤에 선다.
        EnqueueResult gen2 = engine.enqueue(QUEUE_ID, IDENTIFIER);
        assertThat(gen2.isOk()).as("게이트가 풀렸으므로 신규여야 한다").isTrue();
        String tokenId2 = gen2.getTokenId();
        long seq2 = gen2.getSeq();
        issuedTokenIds.add(tokenId2);
        assertThat(tokenId2).isNotEqualTo(tokenId1);

        // 🔴 240초 창 안에 도착한 gen1의 늦은 complete. DB는 이미 status=2로 확정한 상태다.
        engine.cleanupCompleted(QUEUE_ID, IDENTIFIER, tokenId1, admitToken1, seq1);

        // ① gen2의 자리가 살아 있어야 한다
        assertThat(redis.opsForZSet().score(QueueKeys.waiting(QUEUE_ID), IDENTIFIER))
                .as("gen1의 정리가 gen2의 waiting 자리를 지웠다")
                .isNotNull()
                .isEqualTo((double) seq2);

        // ② gen2의 중복 게이트가 살아 있어야 한다 (지워지면 재-enqueue가 과금 한 건을 더 만든다)
        assertThat((String) redis.opsForHash().get(QueueKeys.tokens(QUEUE_ID), IDENTIFIER))
                .as("gen1의 정리가 gen2의 게이트를 지웠다")
                .isNotNull()
                .startsWith(tokenId2 + "|");

        // ③ 🔑 사용자 체감 — gen2의 폴링이 계속 통과해야 한다 (실패하면 조용한 404다)
        assertThat(engine.verifyWaiting(QUEUE_ID, seq2, tokenId2, false, NOW + 70_000))
                .as("축출당한 사용자는 이유도 모른 채 폴링 404를 받는다")
                .isTrue();

        // ④ gen1 정리는 여전히 돼야 한다 — 가드를 과하게 넓히면 여기가 남는다
        assertThat(redis.opsForZSet().score(QueueKeys.admitted(QUEUE_ID), seq1 + "|" + IDENTIFIER))
                .as("자기 회차의 admitted 멤버는 무조건 지워야 한다")
                .isNull();

        // ⑤ 같은 이유. 남으면 완료된 admitToken으로 verify가 최대 60초 계속 통과한다
        assertThat(redis.hasKey(QueueKeys.admitByToken(QUEUE_ID, tokenId1))).isFalse();
        assertThat(redis.hasKey(QueueKeys.admitByAdmit(QUEUE_ID, admitToken1))).isFalse();
    }

    /**
     * 구분자 없는 <b>구식</b> {@code tokens} 값({@code "tokenId"}만, {@code |issuedAt} 없음)도 대조된다.
     *
     * <p>회차 대조가 생기면서 "값을 어떻게 해석하나"가 정리의 전제가 됐다. 이 분기를 {@code sep}이
     * 없을 때 <b>미스 취급</b>으로 조이면, 롤링 배포 중 남은 구 포맷 값을 가진 완료자의 중복 게이트가
     * 영영 안 풀려 <b>영구 락아웃</b>이 된다 — 대조를 넣기 전(무조건 HDEL)보다 나빠지는 유일한
     * 지점이다. {@code poll_verify.lua}가 같은 규약을 쓰고 같은 이유로 단언을 갖고 있다
     * ({@code PollRedisAdapterIntegrationTest#verifyWaiting_legacyTokenValue}).
     */
    @Test
    @DisplayName("cleanupCompleted: 구분자 없는 구식 Hash 값(tokenId만)도 대조돼 게이트가 풀린다")
    void cleanupCompleted_legacyTokenValue() {
        String legacyTokenId = "tok_legacy";
        issuedTokenIds.add(legacyTokenId);
        redis.opsForHash().put(QueueKeys.tokens(QUEUE_ID), IDENTIFIER, legacyTokenId);   // issuedAt 없음

        engine.cleanupCompleted(QUEUE_ID, IDENTIFIER, legacyTokenId, "adm_legacy", 1L);

        assertThat(redis.opsForHash().hasKey(QueueKeys.tokens(QUEUE_ID), IDENTIFIER))
                .as("미스 취급하면 게이트가 안 풀려 영구 락아웃이다")
                .isFalse();
    }
}
