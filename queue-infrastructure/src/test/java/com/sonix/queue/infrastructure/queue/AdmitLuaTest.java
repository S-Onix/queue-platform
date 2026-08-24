package com.sonix.queue.infrastructure.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code admit.lua} 분기 검증 (실제 Redis Lua).
 *
 * <p><b>왜 스크립트를 여기서 직접 로드하는가:</b> {@code RedisConfig}의 스크립트 빈 등록과
 * {@code RedisQueueEngine.admit()}은 후속 작업(U4)이다. 스크립트만 먼저 들어온 상태에서
 * 분기를 검증하려면 테스트가 직접 로드하는 편이 운영 배선을 앞당기지 않는다.
 *
 * <p>검증 명제 셋 — 전부 조용히 깨지는 분기다.
 * <ul>
 *   <li>HGET 미스는 <b>원래 seq로 대기열에 되돌아온다</b> (안 되돌리면 그 사람은 대기열에서도
 *       빠지고 admitToken도 못 받아 사라진다 — §80이 중간 DB 확인을 폐기한 이유)</li>
 *   <li>같은 requestId 재시도는 REPLAY이고 <b>대기열을 건드리지 않는다</b></li>
 *   <li>watermark는 현재값보다 클 때만 오른다 (동시 admit이 전광판을 뒤로 돌리지 않는다)</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
class AdmitLuaTest {

    private static final String QUEUE_ID = "test_q_admit_lua";
    private static final String EXPIRES_AT = "1755530000000";
    private static final String ADMIT_TTL_MS = "60000";
    private static final String IDEM_TTL_MS = "300000";

    private static final String WAITING = QueueKeys.waiting(QUEUE_ID);
    private static final String TOKENS = QueueKeys.tokens(QUEUE_ID);
    private static final String ADMITTED = QueueKeys.admitted(QUEUE_ID);
    private static final String WATERMARK = QueueKeys.admitWatermark(QUEUE_ID);

    @Autowired private StringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> admitScript = new DefaultRedisScript<>(
            readScript(), List.class);

    private static String readScript() {
        try (var in = new ClassPathResource("lua/admit.lua").getInputStream()) {
            return new String(in.readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("admit.lua 로드 실패", e);
        }
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redis.delete(List.of(WAITING, TOKENS, ADMITTED, WATERMARK,
                QueueKeys.admitIdem(QUEUE_ID, "req-1"),
                QueueKeys.admitByToken(QUEUE_ID, "tok_a"),
                QueueKeys.admitByToken(QUEUE_ID, "tok_b"),
                QueueKeys.admitByAdmit(QUEUE_ID, "adm_1"),
                QueueKeys.admitByAdmit(QUEUE_ID, "adm_2"),
                QueueKeys.admitByAdmit(QUEUE_ID, "adm_3")));
    }

    @SuppressWarnings("unchecked")
    private List<Object> admit(String requestId, int count, String... admitTokens) {
        String[] args = new String[7 + admitTokens.length];
        args[0] = String.valueOf(count);
        args[1] = EXPIRES_AT;
        args[2] = ADMIT_TTL_MS;
        args[3] = QueueKeys.admitIdem(QUEUE_ID, requestId);
        args[4] = IDEM_TTL_MS;
        args[5] = QueueKeys.admitByTokenPrefix(QUEUE_ID);
        args[6] = QueueKeys.admitByAdmitPrefix(QUEUE_ID);
        System.arraycopy(admitTokens, 0, args, 7, admitTokens.length);

        return redis.execute(admitScript, List.of(WAITING, TOKENS, ADMITTED, WATERMARK), (Object[]) args);
    }

    /** 대기열 3명 중 한 명(id-c)은 tokens Hash에 항목이 없다 = HGET 미스. */
    private void seedThreeWaitersOneMissing() {
        redis.opsForZSet().add(WAITING, "id-a", 1);
        redis.opsForZSet().add(WAITING, "id-b", 2);
        redis.opsForZSet().add(WAITING, "id-c", 3);
        redis.opsForHash().put(TOKENS, "id-a", "tok_a|1700000000000");
        redis.opsForHash().put(TOKENS, "id-b", "tok_b|1700000000001");
    }

    @Test
    @DisplayName("HGET 미스인 대기자는 원래 seq로 되돌아오고, 나머지만 admit된다")
    @SuppressWarnings("unchecked")
    void hgetMissIsRolledBackToWaiting() {
        seedThreeWaitersOneMissing();

        List<Object> result = admit("req-1", 3, "adm_1", "adm_2", "adm_3");

        assertThat(result.get(0)).isEqualTo("OK");
        List<List<String>> records = (List<List<String>>) result.get(1);
        // 원소 5번째가 issuedAt이다. 이게 빠지면 Java가 HMGET으로 한 번 더 읽어야 하고,
        // 그마저 없으면 ADMITTED 이벤트의 멱등 키(token_id, issued_at)가 성립하지 않는다.
        assertThat(records).containsExactly(
                List.of("id-a", "tok_a", "1", "adm_1", "1700000000000"),
                List.of("id-b", "tok_b", "2", "adm_2", "1700000000001"));

        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("id-c");
        assertThat(redis.opsForZSet().score(WAITING, "id-c")).isEqualTo(3.0);

        assertThat(redis.opsForZSet().range(ADMITTED, 0, -1)).containsExactly("1|id-a", "2|id-b");
        assertThat(redis.opsForValue().get(QueueKeys.admitByToken(QUEUE_ID, "tok_a"))).isEqualTo("adm_1");
        // 값이 "tokenId|seq|issuedAt|identifier"다 — verify가 **DB를 한 번도 읽지 않고**
        // 신원(identifier)을 답하고 COMPLETED 이벤트(seq·issuedAt 필요)까지 만들기 위한 것이다.
        // 🔴 identifier가 맨 뒤인 것이 규약이다 — Tenant 자유 문자열이라 '|'가 들어올 수 있고,
        //    앞 세 값에는 없다. 가변 필드를 중간에 두면 읽는 쪽의 경계가 무너진다.
        assertThat(redis.opsForValue().get(QueueKeys.admitByAdmit(QUEUE_ID, "adm_1")))
                .isEqualTo("tok_a|1|1700000000000|id-a");
        assertThat(redis.opsForValue().get(WATERMARK)).isEqualTo("2");

        // 되돌린 사람 몫의 후보는 버려진다 (Java가 미리 만든 admitToken은 채택될 때만 쓴다)
        assertThat(redis.hasKey(QueueKeys.admitByAdmit(QUEUE_ID, "adm_3"))).isFalse();
    }

    @Test
    @DisplayName("같은 requestId 재시도는 REPLAY — 저장된 payload 그대로, 대기열은 그대로")
    void sameRequestIdReplaysWithoutPopping() {
        seedThreeWaitersOneMissing();

        List<Object> first = admit("req-1", 3, "adm_1", "adm_2", "adm_3");
        List<Object> second = admit("req-1", 3, "adm_9", "adm_8", "adm_7");

        assertThat(second.get(0)).isEqualTo("REPLAY");
        assertThat(second.get(1)).isEqualTo(first.get(1));
        assertThat(redis.opsForZSet().range(WAITING, 0, -1)).containsExactly("id-c");
    }

    @Test
    @DisplayName("watermark는 현재값보다 클 때만 오른다 (전광판이 뒤로 가지 않는다)")
    void watermarkNeverGoesBackwards() {
        seedThreeWaitersOneMissing();
        redis.opsForValue().set(WATERMARK, "999");

        admit("req-1", 3, "adm_1", "adm_2", "adm_3");

        assertThat(redis.opsForValue().get(WATERMARK)).isEqualTo("999");
    }
}
