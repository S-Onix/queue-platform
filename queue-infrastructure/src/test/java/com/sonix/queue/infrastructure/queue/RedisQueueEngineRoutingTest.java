package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.QueueBoard;
import com.sonix.queue.domain.queue.ReclaimedToken;
import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 큐 단위 이중 라우팅 검증 (§75, 안 a″) — <b>실제 Cluster A/B 두 대</b>에 붙는다.
 *
 * <p>다른 통합 테스트는 전부 3인자(단일 클러스터) 생성자를 쓰므로 라우팅 분기를 한 줄도 지나지
 * 않는다({@code cluster1 == cluster2}에서 즉시 반환). 이 클래스만 두 템플릿을 서로 다르게 넣어
 * 팬아웃·DB 폴백·맵 캐싱을 실제로 밟는다.
 *
 * <p>Redis 두 클러스터가 모두 떠 있어야 한다(로컬: 7001-7008 / 8001-8008,
 * {@code doc/INFRA_SETUP.md} §6.5). 큐 키는 {@code q_dev_*}를 쓰고 매 테스트 뒤 지운다.
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class RedisQueueEngineRoutingTest {

    @Autowired
    @Qualifier("stringRedisTemplate")
    private StringRedisTemplate cluster1;

    @Autowired
    @Qualifier("cluster2StringRedisTemplate")
    private StringRedisTemplate cluster2;

    @Autowired
    @Qualifier("enqueueBulkScript")
    private RedisScript<List> enqueueBulkScript;

    @Autowired
    @Qualifier("pollVerifyScript")
    private RedisScript<Long> pollVerifyScript;

    @Autowired
    @Qualifier("admitScript")
    private RedisScript<List> admitScript;

    @Autowired
    @Qualifier("admitExpireScript")
    private RedisScript<List> admitExpireScript;
    @Autowired
    @Qualifier("inactiveExpireScript")
    private RedisScript<List> inactiveExpireScript;

    private static final String ON_CLUSTER2 = "q_dev_route_on_b";
    private static final String BRAND_NEW = "q_dev_route_new";
    private static final String NOT_A_QUEUE = "q_dev_route_ghost";
    private static final long NOW = 1_700_000_000_000L;

    private final QueueJpaRepository queueJpaRepository = mock(QueueJpaRepository.class);

    private RedisQueueEngine engine() {
        return new RedisQueueEngine(cluster1, cluster2, queueJpaRepository,
                enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript, inactiveExpireScript);
    }

    @AfterEach
    void cleanup() {
        for (String queueId : List.of(ON_CLUSTER2, BRAND_NEW, NOT_A_QUEUE)) {
            for (StringRedisTemplate redis : List.of(cluster1, cluster2)) {
                redis.delete(List.of(
                        QueueKeys.waiting(queueId), QueueKeys.seq(queueId),
                        QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId),
                        QueueKeys.admitted(queueId), QueueKeys.admitWatermark(queueId),
                        QueueKeys.pacing(queueId),
                        QueueKeys.admitIdem(queueId, "req-1"),
                        QueueKeys.admitByToken(queueId, "tok_1")));
            }
        }
    }

    /** cluster2에만 존재하는 큐를 seed. seq 키가 소유권 증거다. */
    private void seedOnCluster2(String queueId) {
        cluster2.opsForValue().set(QueueKeys.seq(queueId), "1");
        cluster2.opsForZSet().add(QueueKeys.waiting(queueId), "user_1", 1d);
        cluster2.opsForHash().put(QueueKeys.tokens(queueId), "user_1", "tok_1|" + NOW);
    }

    @Test
    @DisplayName("읽기: cluster2에만 있는 큐를 팬아웃으로 찾아낸다 (cluster1만 보면 빈 결과가 났을 것)")
    void readStatus_findsQueueOnCluster2() {
        seedOnCluster2(ON_CLUSTER2);
        cluster2.opsForValue().set(QueueKeys.admitWatermark(ON_CLUSTER2), "7");
        RedisQueueEngine engine = engine();

        // cluster1에는 이 큐가 없다 — 라우팅이 없으면 여기서 빈 결과(404)가 나온다.
        assertThat(cluster1.hasKey(QueueKeys.seq(ON_CLUSTER2))).isFalse();

        QueueBoard board = engine.readStatus(ON_CLUSTER2).orElseThrow();

        assertThat(board.lastAdmittedSeq()).isEqualTo(7L);
        // 읽기 경로는 DB를 절대 건드리지 않는다 — 인증 없는 폴링(최대 15만/s)의 증폭 경로가 된다.
        verifyNoInteractions(queueJpaRepository);
    }

    @Test
    @DisplayName("읽기: EVAL(poll_verify)도 소유 클러스터에서 실행된다")
    void verifyWaiting_runsOnOwningCluster() {
        seedOnCluster2(ON_CLUSTER2);
        RedisQueueEngine engine = engine();

        assertThat(engine.verifyWaiting(ON_CLUSTER2, 1, "tok_1", true, NOW)).isTrue();

        // keepalive 기록이 cluster2에 남았다 = 스크립트가 거기서 돌았다는 직접 증거
        assertThat(cluster2.opsForZSet().score(QueueKeys.lastActive(ON_CLUSTER2), "1"))
                .isEqualTo((double) NOW);
        assertThat(cluster1.hasKey(QueueKeys.lastActive(ON_CLUSTER2))).isFalse();
        verifyNoInteractions(queueJpaRepository);
    }

    /**
     * 🔴 §82의 이탈 회수는 <b>유일한 경로</b>다. 라우팅을 건너뛰면 cluster2에 배정된 큐의
     * 이탈자가 <b>아무 에러 없이 영원히 회수되지 않는다</b> — cluster1의 빈 `last-active`를 보고
     * 0건을 돌려줄 뿐이라 로그도 지표도 남지 않는다.
     *
     * <p>단일 클러스터 통합 테스트({@code InactiveReclaimTest})는 이 분기를 한 줄도 지나지
     * 않으므로({@code cluster1 == cluster2}에서 즉시 반환) <b>여기가 유일한 방어선</b>이다.
     */
    @Test
    @DisplayName("쓰기: claimInactive(EVAL)도 소유 클러스터에서 실행된다 — cluster1로 가면 조용히 0건이다 (§82)")
    void claimInactive_runsOnOwningCluster() {
        seedOnCluster2(ON_CLUSTER2);
        // 마지막 폴링이 컷오프보다 오래됐다 = 회수 대상
        cluster2.opsForZSet().add(QueueKeys.lastActive(ON_CLUSTER2), "1", NOW - 1);
        RedisQueueEngine engine = engine();

        List<ReclaimedToken> claimed = engine.claimInactive(ON_CLUSTER2, NOW, 500);

        assertThat(claimed).singleElement()
                .extracting(ReclaimedToken::identifier, ReclaimedToken::tokenId)
                .containsExactly("user_1", "tok_1");

        // 부수효과가 cluster2에만 남았다 = 스크립트가 거기서 돌았다는 직접 증거.
        // (라우팅을 건너뛰면 cluster1의 빈 키에서 0건이 나오고 아래 셋이 전부 뒤집힌다)
        assertThat(cluster2.opsForZSet().score(QueueKeys.waiting(ON_CLUSTER2), "user_1")).isNull();
        assertThat(cluster2.opsForHash().hasKey(QueueKeys.tokens(ON_CLUSTER2), "user_1")).isFalse();
        assertThat(cluster1.hasKey(QueueKeys.lastActive(ON_CLUSTER2))).isFalse();
    }

    @Test
    @DisplayName("쓰기: admit(EVAL)도 소유 클러스터에서 실행된다 — cluster1로 가면 빈 대기열에서 0명을 뽑는다")
    void admit_runsOnOwningCluster() {
        seedOnCluster2(ON_CLUSTER2);
        RedisQueueEngine engine = engine();

        AdmitResult result = engine.admit(ON_CLUSTER2, "req-1", 1, NOW);

        assertThat(result.records()).extracting(AdmitResult.AdmitRecord::tokenId).containsExactly("tok_1");

        // 부수효과가 cluster2에만 남았다 = 스크립트가 거기서 돌았다는 직접 증거.
        // (라우팅을 건너뛰면 cluster1의 빈 큐에서 0건이 나오고, 아래 세 단언이 뒤집힌다)
        assertThat(cluster2.opsForZSet().size(QueueKeys.admitted(ON_CLUSTER2))).isEqualTo(1L);
        assertThat(cluster1.hasKey(QueueKeys.admitted(ON_CLUSTER2))).isFalse();
        assertThat(cluster1.hasKey(QueueKeys.admitWatermark(ON_CLUSTER2))).isFalse();

        cluster2.delete(QueueKeys.admitByAdmit(ON_CLUSTER2, result.records().get(0).admitToken()));
    }

    @Test
    @DisplayName("쓰기: Redis에 키가 하나도 없는 신규 큐는 DB 배정(redis_cluster_no=2)을 따라 cluster2에 만들어진다")
    void executeBulkLua_newQueueFollowsDbAssignment() {
        when(queueJpaRepository.findRedisClusterNoByQueueId(BRAND_NEW)).thenReturn(Optional.of(2));
        RedisQueueEngine engine = engine();

        engine.executeBulkLua(BRAND_NEW,
                List.of(new PendingEnqueue(BRAND_NEW, "user_1", "tok_1")),
                1000L, Instant.ofEpochMilli(NOW));

        // 여기서 cluster1로 떨어지면 배정이 통째로 무의미해진다.
        assertThat(cluster2.opsForZSet().size(QueueKeys.waiting(BRAND_NEW))).isEqualTo(1L);
        assertThat(cluster1.hasKey(QueueKeys.waiting(BRAND_NEW))).isFalse();
        assertThat(cluster1.hasKey(QueueKeys.seq(BRAND_NEW))).isFalse();
    }

    @Test
    @DisplayName("쓰기: DB 배정이 1이면 cluster1에 만들어진다")
    void executeBulkLua_newQueueOnCluster1() {
        when(queueJpaRepository.findRedisClusterNoByQueueId(BRAND_NEW)).thenReturn(Optional.of(1));
        RedisQueueEngine engine = engine();

        engine.executeBulkLua(BRAND_NEW,
                List.of(new PendingEnqueue(BRAND_NEW, "user_1", "tok_1")),
                1000L, Instant.ofEpochMilli(NOW));

        assertThat(cluster1.opsForZSet().size(QueueKeys.waiting(BRAND_NEW))).isEqualTo(1L);
        assertThat(cluster2.hasKey(QueueKeys.waiting(BRAND_NEW))).isFalse();
    }

    @Test
    @DisplayName("소유자를 확정 못 한 queueId는 맵에 쌓이지 않는다 (임의 문자열로 맵을 부풀릴 수 없다)")
    void unknownQueueIsNotCached() {
        when(queueJpaRepository.findRedisClusterNoByQueueId(anyString())).thenReturn(Optional.empty());
        RedisQueueEngine engine = engine();

        // 존재하지 않는 큐: 읽기는 빈 결과, 쓰기는 cluster1로 떨어지되 둘 다 기억하지 않는다.
        assertThat(engine.readStatus(NOT_A_QUEUE)).isEmpty();
        assertThat(engine.ownerCacheSize()).isZero();

        engine.executeBulkLua(NOT_A_QUEUE,
                List.of(new PendingEnqueue(NOT_A_QUEUE, "user_1", "tok_1")),
                1000L, Instant.ofEpochMilli(NOW));
        assertThat(engine.ownerCacheSize()).isZero();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 장애 격리: cluster1이 응답하지 못해도 cluster2 큐는 살아 있어야 한다.
    //
    // 프로브가 예외를 그대로 올리면 cluster1 프로브(먼저 호출)에서 끊겨 cluster2 프로브에
    // 도달조차 못 한다. (a″)를 고른 이유가 장애 격리이므로 여기가 새면 안 된다.
    // cluster1은 hasKey에서 던지는 mock으로 대체한다 — 실 노드를 죽이는 건 공유 인프라라 불가.
    // ────────────────────────────────────────────────────────────────────────

    private RedisQueueEngine engineWithDeadCluster1() {
        StringRedisTemplate dead = mock(StringRedisTemplate.class);
        when(dead.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("cluster1 down"));
        return new RedisQueueEngine(dead, cluster2, queueJpaRepository,
                enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript, inactiveExpireScript);
    }

    @Test
    @DisplayName("읽기: cluster1 프로브가 예외를 던져도 cluster2 소유 큐는 정상 조회된다")
    void readStatus_survivesCluster1Failure() {
        seedOnCluster2(ON_CLUSTER2);
        cluster2.opsForValue().set(QueueKeys.admitWatermark(ON_CLUSTER2), "7");
        RedisQueueEngine engine = engineWithDeadCluster1();

        QueueBoard board = engine.readStatus(ON_CLUSTER2).orElseThrow();

        assertThat(board.lastAdmittedSeq()).isEqualTo(7L);
    }

    @Test
    @DisplayName("쓰기: cluster1 프로브가 예외를 던져도 DB 배정 폴백이 살아 cluster2에 만들어진다")
    void executeBulkLua_survivesCluster1Failure() {
        when(queueJpaRepository.findRedisClusterNoByQueueId(BRAND_NEW)).thenReturn(Optional.of(2));
        RedisQueueEngine engine = engineWithDeadCluster1();

        engine.executeBulkLua(BRAND_NEW,
                List.of(new PendingEnqueue(BRAND_NEW, "user_1", "tok_1")),
                1000L, Instant.ofEpochMilli(NOW));

        assertThat(cluster2.opsForZSet().size(QueueKeys.waiting(BRAND_NEW))).isEqualTo(1L);
    }

    @Test
    @DisplayName("프로브 실패는 '소유자 아님'이 아니라 '모름'이다 — 맵에 잘못된 소유권을 남기지 않는다")
    void probeFailureIsNotRecordedAsOwnership() {
        when(queueJpaRepository.findRedisClusterNoByQueueId(anyString())).thenReturn(Optional.empty());
        RedisQueueEngine engine = engineWithDeadCluster1();

        // cluster1은 예외, cluster2에는 없는 큐 → 아무도 소유를 증명하지 못했다.
        // 읽기 폴백 대상이 그 죽은 cluster1이라 호출 자체는 실패한다(장애가 드러나는 게 맞다).
        // 여기서 보는 것은 그 실패가 아니라 '맵에 무엇이 남았는가'다.
        catchThrowable(() -> engine.readStatus(NOT_A_QUEUE));

        assertThat(engine.ownerCacheSize())
                .as("모름을 기록하면 cluster1이 회복돼도 틀린 라우팅이 고착된다")
                .isZero();
    }

    @Test
    @DisplayName("소유자를 한 번 확인하면 맵에 남아, 이후 요청은 다시 팬아웃하지 않는다")
    void ownerIsCachedAfterFirstProbe() {
        seedOnCluster2(ON_CLUSTER2);
        RedisQueueEngine engine = engine();

        assertThat(engine.ownerCacheSize()).isZero();
        engine.readStatus(ON_CLUSTER2);
        assertThat(engine.ownerCacheSize()).isEqualTo(1);

        // 소유 클러스터에서 큐를 통째로 지워도 캐시는 유지된다 — 큐는 옮기지 않으므로(D27-2)
        // 이 값은 불변이고, 무효화 로직이 없다는 사실을 여기서 못박는다.
        cleanupQueue(cluster2, ON_CLUSTER2);
        engine.readStatus(ON_CLUSTER2);
        assertThat(engine.ownerCacheSize()).isEqualTo(1);
    }

    private static void cleanupQueue(StringRedisTemplate redis, String queueId) {
        redis.delete(List.of(QueueKeys.waiting(queueId), QueueKeys.seq(queueId),
                QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId)));
    }
}
