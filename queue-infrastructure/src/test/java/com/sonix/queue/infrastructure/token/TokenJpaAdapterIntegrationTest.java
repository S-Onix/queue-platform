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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
    @Autowired private PlatformTransactionManager txManager;

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
        // 🔴 이벤트가 실어온 ADMITTED_AT이 **아니다** (§90). admitted_at은 술어의 좌변이고
        //    우변이 전부 MySQL 시계라, 값도 MySQL이 찍는다 — 앱 시계로 쓰면 한 창을 두 시계로 잰다.
        //    그래서 단정할 수 있는 것은 "이벤트 값과 같다"가 아니라 "지금 근처"다.
        assertThat(admittedAtOf(tokenId))
                .as("admitted_at은 UTC_TIMESTAMP(3)이 찍는다 — 이벤트의 %s가 아니다", ADMITTED_AT)
                .isNotNull()
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(1, ChronoUnit.MINUTES));
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

        // 🔴 §90 이후 **status만 보면 부족하다.** admitted_at 값을 MySQL이 찍으므로,
        //    status 가드를 잃으면 재전달 시각의 UTC_TIMESTAMP(3)가 새로 박힌다 —
        //    리밸런스가 200초 뒤 일어나면 complete 창이 **연장되고** ReconcileJob 만료가 밀린다.
        //    §90 이전엔 payload 값이 같아 무해했던 것이 이제 **원장 시각 변조**다.
        LocalDateTime before = admittedAtOf(tokenId);

        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 5)));

        assertThat(statusOf(tokenId)).as("허용 출발이 0뿐이라 2는 그대로다").isEqualTo(2);
        assertThat(admittedAtOf(tokenId))
                .as("재전달이 admitted_at을 다시 찍으면 complete 창이 연장된다 (§90)")
                .isEqualTo(before);
    }

    /**
     * 허용 출발이 아닌 전이는 <b>조용히 no-op</b>이다 (예외 아님). 예외로 만들면 재전달 한 건이
     * 배치 전체를 DLT로 끌고 간다.
     *
     * <p>🔧 <b>예시를 바꿨다 (§91).</b> 예전엔 "WAITING(0)에 COMPLETED가 와도 0"으로 이 성질을
     * 보였는데, §91이 COMPLETED 가드를 {@code status IN (0, 1)}로 넓히면서 <b>그 전이는 이제
     * 허용이다</b>(순서 역전이 실재하기 때문 — {@code transition_completedBeforeAdmittedStillCompletes}).
     * 성질 자체는 그대로라, 여전히 허용 출발이 아닌 {@code EXPIRED(4) → COMPLETED}로 옮겼다.
     * 🔑 <b>4를 배제하는 것은 의도다</b> — 넓히면 이미 확정된 만료를 완료로 뒤집는 새 결함이 된다.
     */
    @Test
    @DisplayName("허용 출발이 아니면 상태가 바뀌지 않는다 — EXPIRED(4)에 COMPLETED가 와도 4")
    void transition_guardBlocksWrongOrigin() {
        String tokenId = "tok_guard_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 9)));
        adapter.applyTransition(TokenEventType.EXPIRED, List.of(
                expired(tokenId, 9, ExpiredReason.WAITING_TTL)));
        assertThat(statusOf(tokenId)).isEqualTo(4);

        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 9, TokenStatus.COMPLETED, "adm_x", null)));

        assertThat(statusOf(tokenId)).as("COMPLETED의 허용 출발은 0·1뿐 — 4는 배제된다").isEqualTo(4);
        assertThat(completedAtOf(tokenId)).as("no-op이므로 완료 시각도 안 찍힌다").isNull();
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
    /**
     * 🔴 <b>§90 회귀 가드 (ODKU 경로) — 이 테스트가 빨개지면 원장이 깨진다.</b>
     *
     * <p>실측 재현(2026-09-09): admit을 처리한 API 서버 시계가 <b>398초</b> 뒤처져 있으면 그 서버가
     * 방금 admit했는데도 {@code admitted_at}에 398초 전 시각이 박혔다. complete 술어는
     * {@code admitted_at > UTC_TIMESTAMP(3) - INTERVAL 300 SECOND}라 <b>admit 0초 뒤의 complete도
     * 0행</b>이었고, 그 행은 ReconcileJob이 {@code status = 4}로 확정하는데 사용자는 Redis 폴백으로
     * 200을 받아 <b>{@code status=4 / completed_at=NULL}로 영구 고정</b>됐다(멱등성까지 깨진다).
     *
     * <p>🪤 <b>{@code @Transactional}을 붙이지 마라.</b> 붙이면 {@code saveAllIfAbsent}가 flush되지
     * 않은 채로 {@code applyTransition}의 raw JDBC가 돌아 <b>충돌이 없어 INSERT 경로</b>를 탄다.
     * 운영의 주 경로는 ODKU(선행 WAITING 행이 있다)이고, 둘은 값의 출처가 서로 다른 코드다
     * (SET 절 vs VALUES 절). 실제로 이 함정 때문에 가드가 한동안 엉뚱한 경로를 지키고 있었다.
     */
    @Test
    @DisplayName("§90(ODKU): admit 서버 시계가 398초 뒤처져도 complete 창은 300초 그대로다")
    void transition_admittedAtIgnoresSkewedEventClock() {
        String tokenId = "tok_skew_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 7)));   // 커밋된다 → 아래는 ODKU 경로

        // 시계가 398초 뒤처진 API 서버가 만든 payload. 실제 admit은 "지금"이다.
        LocalDateTime skewed = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(398);
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(
                transition(tokenId, 7, TokenStatus.ADMIT_ISSUED, admitTokenFor(tokenId), skewed)));

        assertThat(admittedAtOf(tokenId))
                .as("payload의 뒤처진 시각이 아니라 MySQL 시계가 찍혀야 한다")
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(1, ChronoUnit.MINUTES));

        // 🔑 값이 바뀐 것만 재면 "창이 실제로 열렸는지"는 모른다. 그게 이 건의 증상이었다.
        int rows = txTemplate().execute(tx -> adapter.markCompleted(
                QUEUE_ID, tenantId, tokenId, admitTokenFor(tokenId),
                LocalDateTime.now(ZoneOffset.UTC), Token.COMPLETE_VALID_WINDOW_SECONDS));
        assertThat(rows).as("§90 이전엔 여기가 0행이었다 — 그게 원장 손상의 출발점이다").isEqualTo(1);
        assertThat(statusOf(tokenId)).isEqualTo(2);
    }

    /**
     * 🔴 <b>§90 회귀 가드 (INSERT 경로).</b> 선행 WAITING 행이 없으면(= ENQUEUED 유실) ODKU가 아니라
     * INSERT로 들어가고, 그때 값을 정하는 것은 SET 절이 아니라 <b>VALUES 절</b>이다.
     * 한쪽만 고치면 "거의 맞는데 가끔 틀리는" 상태가 되므로 두 경로를 각각 못박는다.
     */
    @Test
    @DisplayName("§90(INSERT): 선행 행이 없어도 admitted_at은 MySQL 시계다")
    void transition_admittedAtIsDbClockOnInsertPath() {
        String tokenId = "tok_skew_ins_" + UUID.randomUUID();
        LocalDateTime skewed = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(398);

        adapter.applyTransition(TokenEventType.ADMITTED, List.of(
                transition(tokenId, 9, TokenStatus.ADMIT_ISSUED, admitTokenFor(tokenId), skewed)));

        assertThat(admittedAtOf(tokenId))
                .as("VALUES 절이 앱 시계를 그대로 넣으면 여기가 398초 전이 된다")
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(1, ChronoUnit.MINUTES));
    }

    /**
     * 🔴 <b>null성은 보존돼야 한다.</b> VALUES 절을 무조건 {@code UTC_TIMESTAMP(3)}로 바꾸면
     * {@code EXPIRED}·{@code COMPLETED}가 신규 행을 만들 때도 {@code admitted_at}이 찍혀,
     * 입장한 적 없는 토큰을 {@code SUM(admitted_at IS NOT NULL)}(= 입장권 개수의 유일한 근거)이
     * 세어 버린다. 과금이 부풀어도 아무도 못 본다.
     */
    @Test
    @DisplayName("§90: admit을 거치지 않은 EXPIRED 신규 행의 admitted_at은 NULL이다")
    void transition_expiredWithoutAdmitLeavesAdmittedAtNull() {
        String tokenId = "tok_sk_en_" + UUID.randomUUID();

        adapter.applyTransition(TokenEventType.EXPIRED, List.of(
                expired(tokenId, 10, ExpiredReason.WAITING_TTL)));

        assertThat(admittedAtOf(tokenId))
                .as("입장한 적 없는 토큰이 SUM(admitted_at IS NOT NULL)에 세어지면 과금이 부푼다")
                .isNull();
    }

    /**
     * 🔴 <b>시계 통일이 만료를 죽이지 않는다.</b> 창 밖(400초)인 행은 여전히 정리 대상이다 —
     * 반대편을 안 재면 "아무것도 만료 안 되는" SQL로 바꿔도 위 가드들은 초록이다.
     */
    @Test
    @DisplayName("§90: 창 밖 행은 그대로 만료된다 — 시계 통일이 만료를 죽이지 않는다")
    void expireStaleAdmitted_stillExpiresOutsideWindow() {
        String tokenId = "tok_skew_exp_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 8)));
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 8)));
        // MySQL이 찍은 admitted_at을 창 밖으로 직접 민다 (앱 시계를 거치지 않는다)
        jdbc.update("UPDATE tokens SET admitted_at = UTC_TIMESTAMP(3) - INTERVAL 400 SECOND "
                + "WHERE token_id = ?", tokenId);

        int expired = txTemplate().execute(tx ->
                adapter.expireStaleAdmitted(QUEUE_ID, Token.COMPLETE_VALID_WINDOW_SECONDS, 100));
        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(tokenId)).isEqualTo(4);
    }

    /**
     * 🔴 <b>§91 회귀 가드 — 이 테스트가 빨개지면 폴백 complete의 1.43%가 다시 원장을 잃는다.</b>
     *
     * <p>실측(2026-09-09, Kafka 오프셋 전수): stuck 7건 <b>전부</b> COMPLETED 오프셋이 ADMITTED보다
     * 앞이었다. {@code admit.lua}가 커밋되면 admitToken이 Redis에 즉시 보이고 폴링은 Redis만 보는데,
     * {@code publishAdmitted}는 그 뒤에 건별 블로킹 {@code .get()}으로 <b>직렬</b> 발행한다
     * (지연 67~128ms). 사용자가 그 사이에 verify·complete를 끝내면 순서가 뒤집힌다.
     *
     * <p>예전 가드({@code status = 1})는 여기서 <b>조용히 no-op</b>이 되어 행이
     * {@code status=1 / completed_at=NULL}로 고착됐고, 300초 뒤 ReconcileJob이 {@code status=4}로
     * 확정했다. <b>사용자는 200을 받아 알 수단이 없었다.</b>
     *
     * <p>🔑 <b>네 컬럼을 다 단정하는 것이 요점이다.</b> {@code status}만 보면
     * {@code admit_token}·{@code admitted_at}이 NULL로 남는 판(= 원장 유실이 과금 누락으로
     * 모양만 바뀌는 판)을 통과시킨다 — 3인 검토가 각각 다른 경로로 지목한 지점이다.
     */
    @Test
    @DisplayName("§91: COMPLETED가 ADMITTED보다 먼저 도착해도 완료가 확정되고 네 컬럼이 다 찬다")
    void transition_completedBeforeAdmittedStillCompletes() {
        String tokenId = "tok_ord_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 21)));      // ENQUEUED만 적용된 상태

        // 순서 역전: COMPLETED가 먼저 (admittedAt은 null — 실제 발행 지점 전부 그렇다)
        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 21, TokenStatus.COMPLETED, admitTokenFor(tokenId), null)));

        assertThat(statusOf(tokenId)).as("status=1 가드였다면 여기가 0으로 남는다").isEqualTo(2);
        assertThat(admitTokenOf(tokenId))
                .as("NULL이면 findCompletedAt이 빈 값을 읽어 complete 재시도가 영구 404가 된다")
                .isEqualTo(admitTokenFor(tokenId));
        assertThat(admittedAtOf(tokenId))
                .as("NULL이면 SUM(admitted_at IS NOT NULL)에서 빠져 입장권이 과소 계상된다")
                .isNotNull();
        assertThat(completedAtOf(tokenId)).isNotNull();

        // 뒤늦게 도착한 ADMITTED는 status=0 가드에 걸려 no-op — 값을 덮어쓰지 않아야 한다
        LocalDateTime admittedAt = admittedAtOf(tokenId);
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 21)));

        assertThat(statusOf(tokenId)).as("늦은 ADMITTED가 완료를 되돌리면 안 된다").isEqualTo(2);
        assertThat(admittedAtOf(tokenId)).as("no-op이므로 값이 그대로여야 한다").isEqualTo(admittedAt);
    }

    /**
     * 🔴 <b>§91 회귀 가드 ②  — `IS NULL` 하위조건과 `status=2` 배제를 지킨다.</b>
     *
     * <p>순서 역전 가드({@code transition_completedBeforeAdmittedStillCompletes})가 <b>못 잡는</b>
     * 두 구멍을 이 테스트가 막는다. 결함 주입으로 확인된 사각지대다 —
     * {@code admitted_at} 줄의 {@code IS NULL}을 지워도, 가드를 {@code IN (0,1,2)}로 넓혀도
     * <b>487건이 전부 초록이었다.</b>
     *
     * <p>🔑 <b>{@code IS NULL}이 없으면 1.43%가 아니라 완료되는 토큰 100%가 망가진다.</b>
     * COMPLETED가 정상 순서로 와도 {@code admitted_at}을 자기 시각으로 덮어써서, 그 컬럼이
     * "admit 시각"이 아니라 "complete 적용 시각"이 된다 →
     * {@code queue_daily_stats}의 대기 시간이 <b>대기 + 체류 시간</b>이 되고,
     * §90이 {@code transition_redeliveryDoesNotResurrect}에서 못박은 성질(재기록하면 complete 창이
     * 연장되고 ReconcileJob 만료가 밀린다)이 COMPLETED 쪽에서 무너진다.
     *
     * <p>🪤 <b>{@code transition_redeliveryDoesNotResurrect}는 이걸 구조적으로 못 잡는다</b> —
     * 비교 기준값을 COMPLETED 적용 <b>뒤에</b> 읽어서, 오염된 값을 기준으로 삼는다.
     * 그래서 여기서는 <b>ADMITTED 직후에</b> 기준값을 잡는다.
     */
    @Test
    @DisplayName("§91: 정상 순서에서 COMPLETED는 admitted_at을 덮지 않고, status=2 재도착도 no-op이다")
    void transition_completedDoesNotOverwriteAdmittedAt() {
        String tokenId = "tok_ovw_" + UUID.randomUUID();
        adapter.saveAllIfAbsent(List.of(waiting(tokenId, 31)));
        adapter.applyTransition(TokenEventType.ADMITTED, List.of(admitted(tokenId, 31)));

        // 🔑 오염 전에 기준값을 잡는다 — 이 한 줄이 J2를 잡는 유일한 이유다
        LocalDateTime admittedAt = admittedAtOf(tokenId);
        assertThat(admittedAt).isNotNull();

        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 31, TokenStatus.COMPLETED, admitTokenFor(tokenId), null)));

        assertThat(statusOf(tokenId)).isEqualTo(2);
        assertThat(admittedAtOf(tokenId))
                .as("IS NULL 조건이 없으면 COMPLETED가 admit 시각을 자기 시각으로 덮는다 (전 토큰 대상)")
                .isEqualTo(admittedAt);
        LocalDateTime completedAt = completedAtOf(tokenId);
        assertThat(completedAt).isNotNull();

        // status=2 배제: 재전달된 COMPLETED가 이미 돌려준 completedAt을 덮으면 안 된다
        adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                transition(tokenId, 31, TokenStatus.COMPLETED, admitTokenFor(tokenId), null)));

        assertThat(completedAtOf(tokenId))
                .as("가드를 IN (0,1,2)로 넓히면 여기가 갈린다 — Tenant에 돌려준 completedAt이 거짓이 된다")
                .isEqualTo(completedAt);
        assertThat(admittedAtOf(tokenId)).isEqualTo(admittedAt);
    }

    /**
     * 🔴 <b>회귀 가드 — 한 트랜잭션 안에서 ENQUEUED와 전이의 <u>실행 순서</u>가 호출 순서와 같은가.</b>
     *
     * <p>실측으로 깨졌던 것이다(2026-09-14). {@code saveAllIfAbsent}가 JPA {@code saveAll}이던 동안에는
     * {@code persist}가 INSERT를 <b>플러시까지 미루고</b> {@code applyTransition}의 raw JDBC는
     * <b>즉시 실행</b>했다. 그래서 한 트랜잭션에 둘을 넣으면 COMPLETED가 먼저 돌아 선행 행이 없고,
     * ODKU가 아니라 <b>INSERT 경로</b>를 탔다. 그 경로의 VALUES에는 {@code completed_at}이 없고
     * COMPLETED 이벤트는 {@code admittedAt = null}을 싣는다 → <b>{@code status=2}인데 두 칸이 NULL</b>.
     *
     * <p>그 상태의 피해는 이 파일이 이미 다른 테스트로 적어 둔 것과 같다 — complete 재시도가 영구 404,
     * {@code SUM(admitted_at IS NOT NULL)}(입장권 개수의 유일한 근거) 과소 계상 = <b>과금 누락</b>.
     *
     * <p>🪤 <b>{@code status}만 보면 안 잡힌다.</b> 깨진 경로도 {@code status = 2}로 끝난다.
     * 실제로 검토에서 한 에이전트가 status만 대조하고 "무해"로 판정했다가 뒤집혔다.
     * <b>반드시 {@code completed_at}·{@code admitted_at}을 단정해라.</b>
     *
     * <p>🪤 <b>{@code TransactionTemplate}이 이 테스트의 핵심이다.</b> 트랜잭션 없이 부르면 두 호출이
     * 각자 커밋돼 순서가 저절로 맞는다 — 결함이 있어도 초록이다. 컨슈머의 {@code persistAll}이
     * 바로 이 "한 트랜잭션" 모양이므로 여기서도 그렇게 태워야 한다.
     */
    @Test
    @DisplayName("🔴 한 트랜잭션에서 ENQUEUED→COMPLETED→ADMITTED를 태워도 원장 두 칸이 채워진다")
    void oneTransaction_enqueueThenTransitions_fillsLedgerColumns() {
        String tokenId = "tok_ord_" + UUID.randomUUID();

        // 컨슈머 persistAll 과 같은 모양: 한 트랜잭션 안에서 도착 순서대로 구간을 친다.
        txTemplate().executeWithoutResult(status -> {
            adapter.saveAllIfAbsent(List.of(waiting(tokenId, 41)));
            adapter.applyTransition(TokenEventType.COMPLETED, List.of(
                    transition(tokenId, 41, TokenStatus.COMPLETED, admitTokenFor(tokenId), null)));
            adapter.applyTransition(TokenEventType.ADMITTED, List.of(
                    transition(tokenId, 41, TokenStatus.ADMIT_ISSUED, admitTokenFor(tokenId), ADMITTED_AT)));
        });

        assertThat(statusOf(tokenId)).as("완료로 끝나야 한다").isEqualTo(TokenStatus.COMPLETED.getStatusCode());
        assertThat(completedAtOf(tokenId))
                .as("ENQUEUED가 전이보다 늦게 실행되면 COMPLETED가 INSERT 경로를 타고 이 칸이 NULL로 굳는다")
                .isNotNull();
        assertThat(admittedAtOf(tokenId))
                .as("여기가 NULL이면 입장권 개수 집계에서 빠져 과금이 누락된다 (§91 SET 절이 안 돈 것)")
                .isNotNull();
    }

    private LocalDateTime completedAtOf(String tokenId) {
        return jdbc.queryForObject("SELECT completed_at FROM tokens WHERE token_id = ?",
                LocalDateTime.class, tokenId);
    }

    /** {@code @Modifying} 쿼리는 트랜잭션을 요구하는데, 클래스에 걸면 seed가 flush되지 않아 경로가 갈린다. */
    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(txManager);
    }

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
