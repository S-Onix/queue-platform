package com.sonix.queue.infrastructure.token;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenStatus;
import com.sonix.queue.infrastructure.adapter.TokenJpaAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verify·complete 네이티브 쿼리 통합 테스트 (실제 MySQL, FRS §6.5·§6.6).
 *
 * <p><b>왜 실제 DB가 필요한가</b>: MockMvc 슬라이스는 리포지토리를 목으로 막아 SQL을 한 줄도
 * 실행하지 않는다. 여기서만 잡히는 것 셋 —
 * <ol>
 *   <li>{@code INTERVAL :param SECOND}의 바인딩. 안 되면 verify/complete가 런타임에 통째로 죽는다</li>
 *   <li>{@code UTC_TIMESTAMP(3)} 기준이 앱 세션 TZ와 무관하게 같은 값을 내는가</li>
 *   <li>{@code admitted_at}이 기준 컬럼이라는 것 — {@code issued_at}으로 쓰면 두 시간 전 값이라
 *       60초 창 판정이 무의미해진다. 아래 fixture가 issued_at은 옛날, admitted_at은 방금으로 갈라 둔다</li>
 * </ol>
 *
 * <p>데이터는 {@code q_dev_*} 네임스페이스를 쓰고 매번 지운다.
 */
@SpringBootTest(classes = TokenJpaTestConfig.class, properties = {
        "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true",
        "spring.datasource.master.username=queueapp",
        "spring.datasource.master.password=queueapp1234",
        "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.replica.username=queueapp",
        "spring.datasource.replica.password=queueapp1234",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenAdmitQueryIntegrationTest {

    private static final String TENANT_KEY = "t_dev_admitq";
    private static final String QUEUE_ID = "q_dev_admitq";
    /** 줄 선 시각은 일부러 과거다 — 유효 창 판정이 이 값을 쓰면 전부 실패해야 맞다. */
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 7, 15, 10, 0, 0);
    private static final int TTL_SECONDS = 60;

    @Autowired private TokenJpaAdapter adapter;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;

    @BeforeAll
    void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                TENANT_KEY, "dev_admitq@test.local", "x", "dev-admitq");
        tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, TENANT_KEY);
        jdbc.update("INSERT IGNORE INTO queues (queue_id, tenant_id, name, max_capacity) VALUES (?, ?, ?, ?)",
                QUEUE_ID, tenantId, "dev-admitq-queue", 100000);
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

    /**
     * admit된 토큰 1건을 심는다. {@code admittedAtOffsetSeconds}만큼 과거의 admit 시각을 준다
     * (0이면 방금). 기준은 {@code UTC_TIMESTAMP(3)}라 서버 TZ와 무관하다.
     */
    private void seedAdmitted(String tokenId, String admitToken, int admittedAtOffsetSeconds) {
        jdbc.update("""
                INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status,
                                    admit_token, issued_at, admitted_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, UTC_TIMESTAMP(3) - INTERVAL ? SECOND)
                """, tokenId, QUEUE_ID, tenantId, "0190e2c1-user", 42L, admitToken,
                ISSUED_AT, admittedAtOffsetSeconds);
    }

    // ── verify ──

    @Test
    @DisplayName("findByTokenId: 신원만 읽는다 — status가 아직 0(컨슈머 랙)이어도 찾아진다")
    void findByTokenId_ignoresStatus() {
        jdbc.update("""
                INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """, "tok_dev_lag", QUEUE_ID, tenantId, "0190e2c1-user", 42L, ISSUED_AT);

        Optional<Token> found = adapter.findByTokenId(QUEUE_ID, tenantId, "tok_dev_lag");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("0190e2c1-user");
        assertThat(found.get().getStatus()).isEqualTo(TokenStatus.WAITING);
    }

    @Test
    @DisplayName("findByTokenId: 다른 tenant의 토큰은 안 보인다 (소유권 술어)")
    void findByTokenId_ownership() {
        seedAdmitted("tok_dev_own", "adm_dev_own", 0);

        assertThat(adapter.findByTokenId(QUEUE_ID, tenantId + 99_999, "tok_dev_own")).isEmpty();
    }

    @Test
    @DisplayName("findAdmittedByAdmitToken: admitted_at이 창 안이면 찾고, 넘으면 못 찾는다")
    void findAdmittedByAdmitToken_window() {
        seedAdmitted("tok_dev_fresh", "adm_dev_fresh", 5);     // 5초 전 admit → 창 안
        seedAdmitted("tok_dev_stale", "adm_dev_stale", 120);   // 120초 전 admit → 창 밖

        assertThat(adapter.findAdmittedByAdmitToken(QUEUE_ID, tenantId, "adm_dev_fresh", TTL_SECONDS))
                .isPresent();
        assertThat(adapter.findAdmittedByAdmitToken(QUEUE_ID, tenantId, "adm_dev_stale", TTL_SECONDS))
                .isEmpty();
    }

    @Test
    @DisplayName("findAdmittedByAdmitToken: issued_at이 오래됐어도 통과한다 — 기준 컬럼은 admitted_at이다")
    void findAdmittedByAdmitToken_notIssuedAt() {
        // issued_at은 2026-07-15(한참 과거), admitted_at은 방금.
        // 기준을 issued_at으로 잘못 잡았다면 이 단언이 깨진다.
        seedAdmitted("tok_dev_old_issue", "adm_dev_old_issue", 1);

        assertThat(adapter.findAdmittedByAdmitToken(QUEUE_ID, tenantId, "adm_dev_old_issue", TTL_SECONDS))
                .isPresent();
    }

    // ── complete ──

    @Test
    @Transactional
    @DisplayName("markCompleted: ADMIT_ISSUED(1) → COMPLETED(2), 1행")
    void markCompleted_fromAdmitIssued() {
        seedAdmitted("tok_dev_c1", "adm_dev_c1", 3);

        int updated = adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c1", "adm_dev_c1",
                LocalDateTime.of(2026, 8, 18, 12, 0, 0), 300);

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("tok_dev_c1")).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("markCompleted: WAITING(0) 복귀분도 받아 준다 — status IN (0,1)")
    void markCompleted_fromWaitingAfterReturn() {
        seedAdmitted("tok_dev_c2", "adm_dev_c2", 90);   // TTL 지나 복귀했다고 가정
        jdbc.update("UPDATE tokens SET status = 0 WHERE token_id = ?", "tok_dev_c2");

        // 90초 전 admit이지만 유효 창 300초 안 → 통과해야 한다(§6.6이 관대한 이유 그 자체)
        int updated = adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c2", "adm_dev_c2",
                LocalDateTime.of(2026, 8, 18, 12, 0, 0), 300);

        assertThat(updated).isEqualTo(1);
        assertThat(statusOf("tok_dev_c2")).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("markCompleted: admitToken이 틀리면 0행")
    void markCompleted_wrongAdmitToken() {
        seedAdmitted("tok_dev_c3", "adm_dev_c3", 3);

        assertThat(adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c3", "adm_dev_WRONG",
                LocalDateTime.of(2026, 8, 18, 12, 0, 0), 300)).isZero();
        assertThat(statusOf("tok_dev_c3")).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("markCompleted: 유효 창(300초)을 넘긴 admit은 0행")
    void markCompleted_outsideWindow() {
        seedAdmitted("tok_dev_c4", "adm_dev_c4", 400);

        assertThat(adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c4", "adm_dev_c4",
                LocalDateTime.of(2026, 8, 18, 12, 0, 0), 300)).isZero();
    }

    @Test
    @Transactional
    @DisplayName("markCompleted: 두 번째 호출은 0행 — status IN (0,1)이 동시 complete를 막는다")
    void markCompleted_secondCallIsZero() {
        seedAdmitted("tok_dev_c5", "adm_dev_c5", 3);
        LocalDateTime at = LocalDateTime.of(2026, 8, 18, 12, 0, 0);

        assertThat(adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c5", "adm_dev_c5", at, 300)).isEqualTo(1);
        assertThat(adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_c5", "adm_dev_c5", at, 300)).isZero();
    }

    // ── verify가 완료시킨 토큰 (실 DB) ──

    /**
     * 🔴 <b>목이 숨겼던 결함을 잡는 자리다.</b>
     *
     * <p>verify가 완료를 확정하게 되면서 {@code complete} API를 거치지 않고 {@code status=2}가 되는
     * 경로가 생겼다. 그 전이는 컨슈머의 ODKU가 적용하는데, 예전엔 {@code completed_at}을 건드리지
     * 않았다("complete API가 이미 채운 값"이라는 전제였다). 그래서 값이 <b>NULL</b>로 남고,
     * 이어지는 {@code complete}의 {@code findCompletedAt}이 빈 값을 읽어 <b>정상 Tenant가 404</b>를 받았다.
     *
     * <p>유닛 테스트는 {@code findCompletedAt}을 {@code Optional.of(...)}로 목킹해서 통과했다 —
     * <b>실제 SQL만이 NULL을 드러낸다.</b> 그래서 이 검증은 실 DB에 있어야 한다.
     *
     * <p>더해서 이 값은 {@code schema.sql}의
     * {@code AVG/MAX(TIMESTAMPDIFF(SECOND, issued_at, completed_at))} 집계에도 쓰인다.
     * NULL이면 verify로 완료된 건이 통계에서 통째로 빠진다.
     */
    @Test
    @Transactional
    @DisplayName("verify 경로(COMPLETED 이벤트)로 완료돼도 completed_at이 채워진다 — 그래야 complete가 멱등하다")
    void completedAt_isFilledByConsumerTransition() {
        seedAdmitted("tok_dev_v1", "adm_dev_v1", 3);

        // verify가 발행한 COMPLETED를 컨슈머가 적용하는 것과 같은 경로다.
        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                Token.reconstruct(null, "tok_dev_v1", QUEUE_ID, tenantId, "0190e2c1-user", 42L,
                        TokenStatus.COMPLETED, null, "adm_dev_v1", false, ISSUED_AT, null)));

        assertThat(statusOf("tok_dev_v1")).isEqualTo(2);
        // 🔴 여기가 핵심 — NULL이면 complete가 404를 준다.
        assertThat(adapter.findCompletedAt(QUEUE_ID, tenantId, "tok_dev_v1", "adm_dev_v1"))
                .isPresent();
    }

    /**
     * 위 전이 뒤에 Tenant가 {@code complete}를 부르는 상황 그대로다.
     * {@code markCompleted}는 {@code status IN (0,1)}이라 0행이고, 그때 <b>최초 완료 시각</b>이
     * 나와야 응답이 참이 된다.
     */
    @Test
    @Transactional
    @DisplayName("verify 완료 후 complete → markCompleted는 0행, findCompletedAt이 값을 준다")
    void completeAfterVerify_findsOriginalCompletedAt() {
        seedAdmitted("tok_dev_v2", "adm_dev_v2", 3);
        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                Token.reconstruct(null, "tok_dev_v2", QUEUE_ID, tenantId, "0190e2c1-user", 42L,
                        TokenStatus.COMPLETED, null, "adm_dev_v2", false, ISSUED_AT, null)));

        assertThat(adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_v2", "adm_dev_v2",
                LocalDateTime.of(2026, 8, 24, 12, 0, 0), 300)).isZero();
        assertThat(adapter.findCompletedAt(QUEUE_ID, tenantId, "tok_dev_v2", "adm_dev_v2"))
                .isPresent();
    }

    /** admitToken이 다르면 남의 완료 시각을 읽지 못한다. */
    @Test
    @Transactional
    @DisplayName("findCompletedAt: admitToken이 틀리면 빈 값")
    void findCompletedAt_wrongAdmitToken() {
        seedAdmitted("tok_dev_v3", "adm_dev_v3", 3);
        adapter.markCompleted(QUEUE_ID, tenantId, "tok_dev_v3", "adm_dev_v3",
                LocalDateTime.of(2026, 8, 24, 12, 0, 0), 300);

        assertThat(adapter.findCompletedAt(QUEUE_ID, tenantId, "tok_dev_v3", "adm_dev_WRONG"))
                .isEmpty();
    }

    private int statusOf(String tokenId) {
        return jdbc.queryForObject("SELECT status FROM tokens WHERE token_id = ?", Integer.class, tokenId);
    }
}
