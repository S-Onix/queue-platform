package com.sonix.queue.infrastructure.token;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>🔴 배치가 정말 한 왕복인가</b>를 왕복 횟수로 증명한다 (실제 MySQL).
 *
 * <p>이 테스트가 존재하는 이유는 이 고장이 <b>아무 신호도 내지 않기</b> 때문이다.
 * ODKU 절에 {@code ?} 플레이스홀더가 하나만 들어가도 Connector/J는 다중행 재작성을 포기하고
 * ({@code QueryInfo} — VALUES 절이 끝난 뒤 파라미터가 나오면 {@code rewritableAsMultiValues = false}),
 * 500건 배치가 <b>500번 왕복</b>으로 퇴화한다. 예외도 로그도 경고도 없다. 결과는 정확히 같고
 * 처리량만 떨어지므로 기능 테스트로는 절대 잡히지 않는다.
 *
 * <p><b>세는 방법:</b> {@code Com_insert}는 <b>세션별</b> 카운터라 커넥션 풀을 1로 묶으면
 * 다른 작업의 잡음이 섞이지 않는다({@code maximum-pool-size=1} — 공유 MySQL이라 GLOBAL은 못 쓴다).
 * 다중행으로 합쳐지면 문장이 1개, 아니면 500개다.
 *
 * <p><b>대조군을 함께 둔다.</b> 성공 단언만 있으면 측정 자체가 고장 났을 때(항상 0을 반환하는 등)
 * 조용히 통과한다. ODKU에 {@code ?}를 넣은 SQL이 정말 500왕복이 되는 것을 같은 방법으로 보여
 * 이 측정이 차이를 볼 수 있음을 증명한다.
 */
@SpringBootTest(classes = TokenJpaTestConfig.class, properties = {
        "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&rewriteBatchedStatements=true",
        "spring.datasource.master.username=queueapp",
        "spring.datasource.master.password=queueapp1234",
        // 🔴 세션 카운터로 세려면 커넥션이 하나여야 한다. 여러 개면 측정 세션과 실행 세션이 갈린다.
        "spring.datasource.master.maximum-pool-size=1",
        "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.replica.username=queueapp",
        "spring.datasource.replica.password=queueapp1234",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("mysql")
class TokenUpsertRewriteTest {

    private static final String TENANT_KEY = "t_ittest_rewrite";
    private static final String QUEUE_ID = "q_ittest_rewrite";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 7, 15, 10, 0, 0);
    private static final int BATCH = 500;   // hibernate batch_size = kafka max-poll-records

    @Autowired private TokenJpaAdapter adapter;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;

    @BeforeAll
    void seedFixtures() {
        jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                TENANT_KEY, "ittest_rewrite@test.local", "x", "ittest");
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
    @DisplayName("🔴 ADMITTED 전이 500건은 INSERT 문장 1개로 합쳐진다 (rewriteBatchedStatements 생존)")
    void transitionBatchIsRewrittenIntoOneStatement() {
        List<Token> tokens = tokens(BATCH);

        long before = comInsert();
        adapter.applyTransition(TokenEventType.ADMITTED, tokens);
        long executed = comInsert() - before;

        assertThat(executed)
                .as("500건이 다중행 INSERT 하나로 합쳐져야 한다. 500이면 ODKU 절에 ?가 섞였거나 "
                        + "URL에서 rewriteBatchedStatements가 빠진 것이다")
                .isEqualTo(1);
        assertThat(countTokens()).as("합쳐졌어도 500행은 그대로 들어간다").isEqualTo(BATCH);
    }

    /**
     * 대조군 — 위 단언이 "측정이 늘 0"이라서 통과한 것이 아님을 보인다.
     * ODKU에 {@code ?}가 하나 있을 뿐인데 왕복이 500배가 된다.
     */
    @Test
    @DisplayName("대조군: ODKU에 ?를 하나 넣으면 같은 배치가 500 문장으로 퇴화한다")
    void placeholderInOdkuSilentlyDisablesRewrite() {
        String sql = """
                INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = IF(status = ?, 1, status)""";
        List<Object[]> args = tokens(BATCH).stream()
                .map(t -> new Object[]{t.getTokenId(), t.getQueueId(), t.getTenantId(), t.getUserId(),
                        t.getSeq(), t.getStatus().getStatusCode(), t.getIssuedAt(), 0})
                .toList();

        long before = comInsert();
        jdbc.batchUpdate(sql, args);
        long executed = comInsert() - before;

        assertThat(executed).as("예외도 로그도 없이 건별 왕복이 된다 — 이것이 이 테스트가 있는 이유")
                .isEqualTo(BATCH);
    }

    // ---------------------------------------------------------------------

    /** 이 세션이 실행한 INSERT 문장 수. 세션별이라 다른 작업의 INSERT는 섞이지 않는다. */
    private long comInsert() {
        // performance_schema.session_status에는 Com_insert가 없다(실측) — SHOW로 읽는다.
        return jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Com_insert'",
                (rs, rowNum) -> rs.getLong("Value"));
    }

    private int countTokens() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE queue_id = ?", Integer.class, QUEUE_ID);
    }

    private List<Token> tokens(int count) {
        String prefix = "tok_rw_" + UUID.randomUUID() + "_";
        return IntStream.range(0, count)
                .mapToObj(i -> Token.transition(TokenStatus.ADMIT_ISSUED, prefix + i, QUEUE_ID, tenantId,
                        "user_" + i, i, ISSUED_AT, "adm_" + i, ISSUED_AT))
                .toList();
    }
}
