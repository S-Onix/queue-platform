package com.sonix.queue.infrastructure.token;

import com.sonix.queue.domain.queue.Token;
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

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * </ul>
 *
 * <p>⚠️ 전제: {@code TokenEntity.@SQLInsert}가 올바른 MySQL 문법이어야 한다
 * ({@code ON DUPLICATE KEY UPDATE token_id = token_id}). 오타가 있으면 이 테스트가 잡아낸다.
 */
@SpringBootTest(classes = TokenJpaTestConfig.class, properties = {
        "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
        "spring.datasource.master.username=queueapp",
        "spring.datasource.master.password=queueapp1234",
        // Replica는 이 테스트에서 안 쓰지만 DataSourceConfig가 두 빈을 요구 → Master로 지정(3307 미기동이어도 무해)
        "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
        "spring.datasource.replica.username=queueapp",
        "spring.datasource.replica.password=queueapp1234",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenJpaAdapterIntegrationTest {

    private static final String TENANT_KEY = "t_ittest_token";
    private static final String QUEUE_ID = "q_ittest_token";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 7, 15, 10, 0, 0);

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
                        "expired_reason, admit_token, redis_sync_needed " +
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
        // insertable=false 3개 컬럼은 INSERT에서 빠지고 DB 기본값이 적용되어야 한다
        assertThat(row.get("expired_reason")).as("EXPIRED 전 → NULL").isNull();
        assertThat(row.get("admit_token")).as("admit 전 → NULL").isNull();
        assertThat(((Number) row.get("redis_sync_needed")).intValue()).as("기본값 0").isEqualTo(0);
    }

    @Test
    @DisplayName("빈 리스트는 아무 일도 하지 않는다")
    void emptyList_isNoop() {
        assertThatCode(() -> adapter.saveAllIfAbsent(List.of())).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------

    private Token waiting(String tokenId, long seq) {
        return Token.issue(tokenId, QUEUE_ID, tenantId, "user_" + tokenId, seq, ISSUED_AT);
    }

    private int countByTokenId(String tokenId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tokens WHERE token_id = ?", Integer.class, tokenId);
    }
}
