package com.sonix.queue.infrastructure.token;

import com.sonix.queue.domain.queue.ExpiredReason;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenStatus;
import com.sonix.queue.infrastructure.adapter.TokenJpaAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TokenJpaAdapter 통합 테스트 (실제 MySQL, localhost:3306).
 *
 * <p>로컬 WSL2 MySQL Master에 연결한다. tokens는 queues→tenants로 FK가 이어지므로
 * {@code @BeforeAll}에서 테스트용 tenant·queue를 심고(재실행 안전하게 INSERT IGNORE),
 * {@code @AfterAll}에서 정리한다. 토큰 row는 매 테스트 후 지운다.
 *
 * <p><b>검증 명제:</b>
 * <ul>
 *   <li>벌크 적재: 서로 다른 토큰 N건 → N row</li>
 *   <li>멱등성(재전달): 같은 (tokenId, issuedAt) 재적재 → row 1개, 예외 없음, 기존 값 no-op 유지</li>
 *   <li>배치 내 dedup: 한 배치 안 중복 식별자 → EntityExistsException 없이 row 1개</li>
 *   <li><b>컬럼 매핑 정확성</b>: @SQLInsert의 '?' 바인딩 순서가 맞아 각 값이 올바른 컬럼에 적재된다</li>
 *   <li><b>전이 가드</b>(§80): 도착 순서가 뒤집혀도, 같은 이벤트가 다시 와도 최종 상태가 같다</li>
 * </ul>
 *
 * <p>⚠️ 전제: {@code TokenEntity.@SQLInsert}가 올바른 MySQL 문법이어야 한다
 * ({@code ON DUPLICATE KEY UPDATE token_id = token_id}). 오타가 있으면 이 테스트가 잡아낸다.
 */
@SpringBootTest(classes = TokenJpaTestConfig.class, properties = {
        "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true",
        "spring.datasource.master.username=queueapp",
        "spring.datasource.master.password=queueapp1234",
        // Replica는 이 테스트에서 안 쓰지만 DataSourceConfig가 두 빈을 요구 → Master로 지정(3307 미기동이어도 무해)
        "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.replica.username=queueapp",
        "spring.datasource.replica.password=queueapp1234",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("mysql")
class TokenJpaAdapterIntegrationTest {

    private static final String TENANT_KEY = "t_ittest_token";
    private static final String QUEUE_ID = "q_ittest_token";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 7, 15, 10, 0, 0);
    /** admit 시각. issued_at과 다른 값이어야 두 칸이 뒤바뀌는 실수를 잡는다. */
    private static final LocalDateTime ADMITTED_AT = LocalDateTime.of(2026, 7, 15, 11, 30, 45, 123_000_000);

    @Autowired private TokenJpaAdapter adapter;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;

    @BeforeAll
    void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                TENANT_KEY, "ittest_token@test.local", "x", "ittest");
        tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, TENANT_KEY);
        jdbc.update("INSERT IGNORE INTO queues (queue_id, tenant_id, name, max_capacity) VALUES (?, ?, ?, ?)",
                QUEUE_ID, tenantId, "ittest-queue", 100000);
    }

    @AfterEach
    void cleanTokens() {
        jdbc.update("DELETE FROM tokens WHERE queue_id = ?", QUEUE_ID);
    }

    @AfterAll
    void cleanupFixtures() {
        jdbc.update("DELETE FROM tokens WHERE queue_id = ?", QUEUE_ID);
        jdbc.update("DELETE FROM queues WHERE queue_id = ?", QUEUE_ID);
        jdbc.update("DELETE FROM tenants WHERE tenant_id = ?", TENANT_KEY);
    }

    // ---------------------------------------------------------------------

    @Test
    @DisplayName("서로 다른 토큰 N건을 벌크 적재하면 N개 row가 생긴다")
    void bulkInsert_distinct() {
        String prefix = "tok_bulk_" + UUID.randomUUID() + "_";
        List<Token> tokens = IntStream.range(0, 5)
                .mapToObj(i -> waiting(prefix + i, i))
                .toList();

        adapter.saveAllIfAbsent(tokens);

        for (int i = 0; i < 5; i++) {
            assertThat(countByTokenId(prefix + i)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 (tokenId, issuedAt) 재적재(outbox 재처리) → row 1개, 예외 없음, 기존 값 유지")
    void idempotent_acrossCalls() {
        String tokenId = "tok_idem_" + UUID.randomUUID();

        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 10)));
        // 재전달: 같은 식별자, seq만 다르게 → ON DUP KEY는 no-op이라 무시되어야 함
        assertThatCode(() -> adapter.saveAllIfAbsent(List.of(waiting(tokenId, 999))))
                .doesNotThrowAnyException();

        assertThat(countByTokenId(tokenId)).as("중복 흡수 → row 1개").isEqualTo(1);
        Long seq = jdbc.queryForObject("SELECT seq FROM tokens WHERE token_id = ?", Long.class, tokenId);
        assertThat(seq).as("no-op UPDATE라 첫 값(10) 유지, 999로 덮어쓰지 않음").isEqualTo(10L);
    }

    @Test
    @DisplayName("한 배치 안에 중복 (tokenId, issuedAt)가 있어도 EntityExistsException 없이 row 1개")
    void dedup_withinBatch() {
        String dup = "tok_dup_" + UUID.randomUUID();
        String other = "tok_other_" + UUID.randomUUID();
        List<Token> batch = List.of(waiting(dup, 1), waiting(dup, 2), waiting(other, 3));

        assertThatCode(() -> adapter.saveAllIfAbsent(batch)).doesNotThrowAnyException();

        assertThat(countByTokenId(dup)).as("중복 식별자는 하나만").isEqualTo(1);
        assertThat(countByTokenId(other)).as("다른 토큰은 정상 적재").isEqualTo(1);
    }

    @Test
    @DisplayName("@SQLInsert 컬럼 매핑 정확성 — 각 값이 올바른 컬럼에 적재된다(바인딩 순서 검증)")
    void columnMapping_isCorrect() {
        String tokenId = "tok_map_" + UUID.randomUUID();
        // 각 컬럼에 서로 구분되는 값 → 순서가 어긋나면 아래 검증 중 하나가 깨진다.
        // tenantId(작은 auto_increment) ≠ seq(500) 이라 두 숫자 컬럼 스왑도 잡힌다.
        Token token = Token.issue(tokenId, QUEUE_ID, tenantId, "user_distinct", 500L, ISSUED_AT);

        adapter.saveAllIfAbsent(List.of(token));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT token_id, queue_id, tenant_id, user_id, seq, status, issued_at, " +
                        "expired_reason, admit_token " +
                        "FROM tokens WHERE token_id = ?", tokenId);

        assertThat(row.get("token_id")).isEqualTo(tokenId);
        assertThat(row.get("queue_id")).isEqualTo(QUEUE_ID);
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(row.get("user_id")).isEqualTo("user_distinct");
        assertThat(((Number) row.get("seq")).longValue()).isEqualTo(500L);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(0); // WAITING
        // 드라이버 설정에 따라 DATETIME은 Timestamp 또는 LocalDateTime으로 올 수 있어 둘 다 처리
        Object issued = row.get("issued_at");
        LocalDateTime issuedAt = (issued instanceof Timestamp ts) ? ts.toLocalDateTime() : (LocalDateTime) issued;
        assertThat(issuedAt).isEqualTo(ISSUED_AT);
        // insertable=false 컬럼은 INSERT에서 빠지고 DB 기본값이 적용되어야 한다
        assertThat(row.get("expired_reason")).as("EXPIRED 전 → NULL").isNull();
        assertThat(row.get("admit_token")).as("admit 전 → NULL").isNull();
    }

    @Test
    @DisplayName("빈 리스트는 아무 일도 하지 않는다")
    void emptyList_isNoop() {
        assertThatCode(() -> adapter.saveAllIfAbsent(List.of())).doesNotThrowAnyException();
    }

    // ── §80 상태 전이 가드 ──

    /**
     * <b>도착 순서 역전.</b> enqueue Lua의 ZADD가 Kafka 발행보다 먼저라 ADMITTED가 ENQUEUED보다
     * 먼저 도착할 수 있고, 프로듀서가 여러 WAS라 브로커 도착 순서도 뒤집힌다.
     * 뒤늦은 ENQUEUED가 status를 0으로 되감으면 그 사람은 <b>입장 자격을 쥔 채 대기자가</b> 된다.
     */
    @Test
    @DisplayName("ADMITTED가 ENQUEUED보다 먼저 도착해도 최종 status는 1이다")
    void transition_outOfOrderArrival() {
        String tokenId = "tok_order_" + UUID.randomUUID();

        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 7)));
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 7)));   // 뒤늦게 도착한 ENQUEUED

        assertThat(countByTokenId(tokenId)).as("행은 하나여야 한다").isEqualTo(1);
        assertThat(statusOf(tokenId)).isEqualTo(1);
        assertThat(admitTokenOf(tokenId)).as("no-op UPSERT가 지우면 안 된다").isEqualTo(admitTokenFor(tokenId));
    }

    /**
     * <b>🔴 SET 절 좌 → 우 평가.</b> {@code status}를 먼저 쓰면 다음 줄의 {@code IF(status = 0, ...)}이
     * 이미 1로 바뀐 값을 보게 되어 거짓이 되고 {@code admit_token}이 영원히 NULL로 남는다.
     * 그러면 complete의 {@code admit_token = ?} 술어가 절대 맞지 않아 complete 전체가 죽는다.
     * 이 테스트가 그 순서를 못박는다 — 기존 WAITING 행에 ADMITTED를 적용하는 정상 경로다.
     */
    @Test
    @DisplayName("WAITING 행에 ADMITTED를 적용하면 status·admit_token·admitted_at이 함께 채워진다")
    void transition_admitFillsAllColumns() {
        String tokenId = "tok_admit_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 3)));

        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 3)));

        assertThat(statusOf(tokenId)).isEqualTo(1);
        assertThat(admitTokenOf(tokenId)).as("SET 절에서 status를 먼저 쓰면 여기가 NULL이 된다")
                .isEqualTo(admitTokenFor(tokenId));
        assertThat(admittedAtOf(tokenId)).isEqualTo(ADMITTED_AT);
    }

    /**
     * <b>재전달 멱등.</b> Kafka는 At-Least-Once이고 컨슈머 리밸런스마다 재처리가 일어난다.
     * 완료된 토큰이 ADMITTED 재전달로 되살아나면 그 사람은 한 번 더 입장한다.
     */
    @Test
    @DisplayName("COMPLETED(2) 행에 ADMITTED가 재도착해도 2가 유지된다")
    void transition_redeliveryDoesNotResurrect() {
        String tokenId = "tok_redeliver_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 5)));
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 5)));
        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 5, TokenStatus.COMPLETED, admitTokenFor(tokenId), null)));
        assertThat(statusOf(tokenId)).isEqualTo(2);

        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 5)));

        assertThat(statusOf(tokenId)).as("허용 출발이 0뿐이라 2는 그대로다").isEqualTo(2);
    }

    /**
     * 허용 출발이 아닌 전이는 <b>조용히 no-op</b>이다 (예외 아님). 예외로 만들면 재전달 한 건이
     * 배치 전체를 DLT로 끌고 간다.
     */
    @Test
    @DisplayName("허용 출발이 아니면 상태가 바뀌지 않는다 — WAITING(0)에 COMPLETED가 와도 0")
    void transition_guardBlocksWrongOrigin() {
        String tokenId = "tok_guard_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 9)));

        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 9, TokenStatus.COMPLETED, "adm_x", null)));

        assertThat(statusOf(tokenId)).as("COMPLETED의 허용 출발은 1뿐").isZero();
    }

    /**
     * 🔴 §36의 핵심 불변식. admitToken TTL 만료자는 {@code status = 1}인데 EXPIRED 가드가
     * {@code status = 0} 전용이라 <b>no-op</b>이어야 한다.
     *
     * <p><b>이게 깨지면 complete가 죽는다.</b> {@code markCompleted}의 술어가
     * {@code status IN (0, 1)}이고 유효 창이 300초인데 admitToken TTL은 60초라,
     * <b>60~300초 구간의 늦은 입장이 정상 경로로 실재</b>한다. status가 4로 넘어가면
     * 그 사람은 Tenant가 이미 사이트에 들여보냈는데도 {@code INVALID_ADMIT_TOKEN}을 받는다.
     * 가드를 {@code IN (0, 1)}로 "고치면" 이 테스트가 잡는다.
     */
    @Test
    @DisplayName("EXPIRED는 ADMIT_ISSUED(1)를 건드리지 않는다 — 늦은 complete를 살린다 (§36)")
    void transition_expiredDoesNotTouchAdmitted() {
        String admittedToken = "tok_exp_a_" + UUID.randomUUID();
        String waitingToken = "tok_exp_w_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(admittedToken, 1), waiting(waitingToken, 2)));
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(admittedToken, 1)));

        adapter.applyTransition(TokenEventType.EXPIRED, List.of(
                transition(admittedToken, 1, TokenStatus.EXPIRED, null, null),
                transition(waitingToken, 2, TokenStatus.EXPIRED, null, null)));

        assertThat(statusOf(admittedToken))
                .as("admitToken 만료자는 1에 머문다 — complete의 300초 창이 살아 있어야 한다")
                .isEqualTo(1);
        assertThat(statusOf(waitingToken))
                .as("waitingTtl·inactiveTtl 만료(출발 0)만 4에 도달한다")
                .isEqualTo(4);
    }

    /**
     * 행이 아예 없을 때는 INSERT다. 컨슈머가 ENQUEUED를 아직 못 받았을 수 있으므로
     * 전이 이벤트가 행을 만들 수 있어야 한다 (그 뒤 ENQUEUED는 no-op으로 흡수된다).
     */
    @Test
    @DisplayName("행이 없으면 도착 상태로 INSERT한다")
    void transition_insertsWhenAbsent() {
        String tokenId = "tok_new_" + UUID.randomUUID();

        adapter.applyTransition(TokenEventType.EXPIRED, List.of(
                transition(tokenId, 4, TokenStatus.EXPIRED, null, null)));

        assertThat(countByTokenId(tokenId)).isEqualTo(1);
        assertThat(statusOf(tokenId)).isEqualTo(4);
    }

    @Test
    @DisplayName("빈 리스트 전이는 아무 일도 하지 않는다")
    void transition_emptyList_isNoop() {
        assertThatCode(() -> adapter.applyTransition(TokenEventType.ADMITTED, List.of()))
                .doesNotThrowAnyException();
    }

    /** ENQUEUED는 @SQLInsert가 맡는다. 여기로 오면 SQL이 없어 조용히 아무 일도 안 하는 대신 터진다. */
    @Test
    @DisplayName("ENQUEUED를 전이 경로로 넘기면 거부한다")
    void transition_rejectsEnqueued() {
        assertThatThrownBy(() -> adapter.applyTransition(TokenEventType.ENQUEUED,
                List.of(waiting("tok_x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------

    /** 실물은 {@code adm_} + UUID(36) = 40자다. 컬럼이 VARCHAR(50)이라 테스트 값도 그 안이어야 한다. */
    private static String admitTokenFor(String tokenId) {
        return "adm_" + tokenId.substring(tokenId.length() - 12);
    }

    private Token admitted(String tokenId, long seq) {
        return transition(tokenId, seq, TokenStatus.ADMIT_ISSUED, admitTokenFor(tokenId), ADMITTED_AT);
    }

    @Test
    @DisplayName("만료 사유가 DB까지 도달한다 — 경로마다 다른 값이고, 안 실으면 영구 소실이다")
    void transition_persistsExpiredReason() {
        String inactive = "tok_r_i_" + UUID.randomUUID();
        String waitingTtl = "tok_r_w_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(inactive, 11), waiting(waitingTtl, 12)));

        adapter.applyTransition(TokenEventType.EXPIRED, List.of(
                expired(inactive, 11, ExpiredReason.INACTIVE),
                expired(waitingTtl, 12, ExpiredReason.WAITING_TTL)));

        // 🔑 셋의 의미가 정반대라(정상 이탈 / 용량 부족) 총계로 합치면 조치로 이어지지 않는다
        assertThat(reasonOf(inactive)).isEqualTo(ExpiredReason.INACTIVE.getCode());
        assertThat(reasonOf(waitingTtl)).isEqualTo(ExpiredReason.WAITING_TTL.getCode());
    }

    @Test
    @DisplayName("사유에도 status 가드가 걸린다 — 없으면 나중에 complete될 토큰에 만료 사유가 박힌다")
    void transition_expiredReasonRespectsStatusGuard() {
        // 🔴 expired_reason을 무조건 쓰면 이 행은 status=2(완료)인데 expired_reason이 채워진다.
        //    그러면 통계가 "완료됐는데 만료된 토큰"이라는 거짓을 말한다.
        String token = "tok_r_g_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(token, 13)));
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(token, 13)));

        adapter.applyTransition(TokenEventType.EXPIRED,
                List.of(expired(token, 13, ExpiredReason.ADMIT_TTL)));

        assertThat(statusOf(token)).as("§36 — 1에 머문다").isEqualTo(1);
        assertThat(reasonOf(token)).as("사유도 안 박힌다. ADMIT_TTL은 ReconcileJob이 ADMIT_STALE로 쓴다").isNull();
    }

    private Token expired(String tokenId, long seq, ExpiredReason reason) {
        return Token.transition(TokenStatus.EXPIRED, tokenId, QUEUE_ID, tenantId, "user_" + tokenId,
                seq, ISSUED_AT, null, null, reason.getCode());
    }

    private Integer reasonOf(String tokenId) {
        return jdbc.queryForObject("SELECT expired_reason FROM tokens WHERE token_id = ?",
                Integer.class, tokenId);
    }

    private Token transition(String tokenId, long seq, TokenStatus status,
                             String admitToken, LocalDateTime admittedAt) {
        return Token.transition(status, tokenId, QUEUE_ID, tenantId, "user_" + tokenId, seq,
                ISSUED_AT, admitToken, admittedAt);
    }

    private int statusOf(String tokenId) {
        return jdbc.queryForObject("SELECT status FROM tokens WHERE token_id = ?", Integer.class, tokenId);
    }

    private String admitTokenOf(String tokenId) {
        return jdbc.queryForObject("SELECT admit_token FROM tokens WHERE token_id = ?", String.class, tokenId);
    }

    private LocalDateTime admittedAtOf(String tokenId) {
        return jdbc.queryForObject("SELECT admitted_at FROM tokens WHERE token_id = ?",
                LocalDateTime.class, tokenId);
    }

    private Token waiting(String tokenId, long seq) {
        return Token.issue(tokenId, QUEUE_ID, tenantId, "user_" + tokenId, seq, ISSUED_AT);
    }

    private int countByTokenId(String tokenId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE token_id = ?", Integer.class, tokenId);
    }
}
