package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisQueueEngine#admit} 통합 검증 (실제 Redis).
 *
 * <p>{@link AdmitLuaTest}가 스크립트 <b>분기</b>를 보는 자리라면, 여기는 그 위의 배선을 본다 —
 * ARGV 조립 순서, admitToken 발급(UUIDv7), 그리고 중첩 배열 → {@link AdmitResult} 파싱.
 * 특히 <b>REPLAY 경로는 cjson 왕복</b>을 거치므로 OK 경로와 파싱이 갈릴 수 있어 따로 본다.
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class RedisQueueEngineAdmitTest {

    private static final String QUEUE_ID = "q_dev_admit_engine";
    private static final long NOW = 1_755_000_000_000L;

    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String ADMITTED = QueueKeys.admitted(QUEUE_ID);
    private static final String WATERMARK = QueueKeys.admitWatermark(QUEUE_ID);

    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisQueueEngine engine;

    /** 테스트가 발급받은 admitToken (값이 랜덤이라 사후에 지우려면 모아둬야 한다). */
    private final List<String> issuedAdmitTokens = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        List<String> keys = new ArrayList<>(List.of(WAITING, TOKENS, ADMITTED, WATERMARK,
                QueueKeys.admitIdem(QUEUE_ID, "req-1"),
                QueueKeys.admitIdem(QUEUE_ID, "req-2"),
                QueueKeys.admitByToken(QUEUE_ID, "tok_a"),
                QueueKeys.admitByToken(QUEUE_ID, "tok_b"),
                QueueKeys.admitByToken(QUEUE_ID, "tok_c")));
        issuedAdmitTokens.forEach(t -> keys.add(QueueKeys.admitByAdmit(QUEUE_ID, t)));
        issuedAdmitTokens.clear();
        redis.delete(keys);
    }

    private AdmitResult admit(String requestId, int count) {
        AdmitResult result = engine.admit(QUEUE_ID, requestId, count, NOW);
        result.records().forEach(r -> issuedAdmitTokens.add(r.admitToken()));
        return result;
    }

    private void seedWaiter(String identifier, long seq, String tokenId) {
        redis.opsForZSet().add(WAITING, identifier, seq);
        if (tokenId != null) {
            redis.opsForHash().put(TOKENS, identifier, tokenId + "|" + NOW);
        }
    }

    @Test
    @DisplayName("앞에서 count명만 뽑고 admitToken(UUIDv7)을 발급한다 — 나머지는 대기열에 남는다")
    void admit_popsExactlyCountFromFront() {
        seedWaiter("id-a", 1, "tok_a");
        seedWaiter("id-b", 2, "tok_b");
        seedWaiter("id-c", 3, "tok_c");

        AdmitResult result = admit("req-1", 2);

        assertThat(result.replay()).isFalse();
        assertThat(result.records()).extracting(AdmitResult.AdmitRecord::identifier)
                .containsExactly("id-a", "id-b");
        assertThat(result.records()).extracting(AdmitResult.AdmitRecord::seq)
                .containsExactly(1L, 2L);
        // issuedAt이 없으면 ADMITTED를 발행할 수 없다(멱등 키의 절반). Lua가 실어 보낸다.
        assertThat(result.records()).extracting(AdmitResult.AdmitRecord::issuedAt)
                .containsOnly(Instant.ofEpochMilli(NOW));

        // admitToken은 tokenId와 같은 UUIDv7이다. 짧은 랜덤이면 verify가 뚫린다(FRS §6.4).
        String admitToken = result.records().get(0).admitToken();
        assertThat(admitToken).startsWith("adm_").hasSize("adm_".length() + 36);
        assertThat(result.records().get(1).admitToken()).isNotEqualTo(admitToken);

        // 양방향 매핑 + 만료 기준이 Redis에 실제로 남았는가
        assertThat(redis.opsForValue().get(QueueKeys.admitByToken(QUEUE_ID, "tok_a"))).isEqualTo(admitToken);
        assertThat(redis.opsForValue().get(QueueKeys.admitByAdmit(QUEUE_ID, admitToken))).isEqualTo("tok_a|id-a");
        assertThat(redis.opsForZSet().score(ADMITTED, "1|id-a")).isEqualTo(NOW + 60_000d);
        assertThat(redis.opsForValue().get(WATERMARK)).isEqualTo("2");

        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("id-c");
    }

    @Test
    @DisplayName("같은 requestId 재시도는 REPLAY — 발급된 admitToken까지 그대로, 대기열은 그대로")
    void admit_sameRequestIdReplays() {
        // identifier는 Tenant 자유 문자열이라 '|'가 들어올 수 있다(§66 D1). REPLAY는 cjson 왕복을
        // 거치므로, 여기서 잘리면 재시도한 Tenant가 다른 사람을 입장시킨다.
        seedWaiter("id|a", 1, "tok_a");
        seedWaiter("id-b", 2, "tok_b");

        AdmitResult first = admit("req-1", 1);
        AdmitResult second = admit("req-1", 1);

        assertThat(second.replay()).isTrue();
        assertThat(second.records()).isEqualTo(first.records());
        assertThat(second.records().get(0).identifier()).isEqualTo("id|a");
        assertThat(second.records().get(0).seq()).isEqualTo(1L);
        // REPLAY는 cjson 왕복이라 issuedAt이 여기서 잘리거나 숫자로 뭉개질 수 있다
        assertThat(second.records().get(0).issuedAt()).isEqualTo(Instant.ofEpochMilli(NOW));

        // 재시도가 두 번 뽑아갔다면 id-b도 사라졌을 것
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("id-b");
    }

    @Test
    @DisplayName("tokens Hash에 없는 대기자는 원래 seq로 되돌아오고, 그 사람 몫 없이 결과가 온다")
    void admit_hgetMissIsRolledBackAndSkipped() {
        seedWaiter("id-a", 1, "tok_a");
        seedWaiter("id-b", 2, null);   // HGET 미스
        seedWaiter("id-c", 3, "tok_c");

        AdmitResult result = admit("req-1", 3);

        assertThat(result.records()).extracting(AdmitResult.AdmitRecord::identifier)
                .containsExactly("id-a", "id-c");
        // 되돌리지 않으면 이 사람은 대기열에서도 빠지고 admitToken도 못 받아 사라진다(§80)
        assertThat(redis.opsForZSet().score(WAITING, "id-b")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("admit 직후 tokenId로 admitToken을 되찾는다 — 폴링이 404 대신 ready를 주는 근거 (U8)")
    void findAdmitTokenByTokenId_returnsIssuedToken() {
        seedWaiter("id-a", 1, "tok_a");

        String issued = admit("req-1", 1).records().get(0).admitToken();

        // admit.lua가 접두사+ARGV로 만든 키를 Java가 QueueKeys로 다시 조립해 읽는다.
        // 두 조립이 어긋나면(단일 출처 붕괴) 정상 입장자가 전부 404가 되는데,
        // 그 어긋남은 실제 Redis 왕복에서만 드러난다.
        assertThat(engine.findAdmitTokenByTokenId(QUEUE_ID, "tok_a")).contains(issued);

        // waiting에도 admit-by-token에도 없으면(= 존재하지 않는 토큰) 빈 Optional → 호출자가 404
        assertThat(engine.findAdmitTokenByTokenId(QUEUE_ID, "tok_none")).isEmpty();
    }

    @Test
    @DisplayName("빈 큐에서 admit해도 멱등키는 저장된다 — 같은 requestId는 계속 0건 (버그 아님, §80)")
    void admit_emptyResultIsStillIdempotent() {
        assertThat(admit("req-1", 5).records()).isEmpty();

        seedWaiter("id-a", 1, "tok_a");

        AdmitResult retry = admit("req-1", 5);
        assertThat(retry.replay()).isTrue();
        assertThat(retry.records()).isEmpty();
        // 새 인원을 받고 싶으면 새 requestId를 쓴다
        assertThat(admit("req-2", 5).records()).hasSize(1);
    }
}
