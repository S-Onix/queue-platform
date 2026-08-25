package com.sonix.queue.infrastructure.billing;

import com.sonix.queue.infrastructure.adapter.BillingJdbcAdapter;
import com.sonix.queue.infrastructure.token.TokenJpaTestConfig;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과금 집계 UPSERT 통합 테스트 (실제 MySQL).
 *
 * <p><b>목으로는 한 줄도 못 잡는 것 셋</b> —
 * <ol>
 *   <li>{@code `year_month`}가 예약어라 백틱이 없으면 {@code ERROR 1064}다.
 *       {@code CREATE TABLE}은 백틱 없이도 통과하므로 스키마만 봐선 안 보인다</li>
 *   <li>{@code INSERT ... SELECT}에 행 별칭({@code AS new})을 못 붙인다 — 서브쿼리 별칭이어야 한다</li>
 *   <li>월 경계가 {@code [1일 00:00, 다음달 1일 00:00)}으로 정확히 잘리는가</li>
 * </ol>
 *
 * <p>과금은 <b>상태를 보지 않는다</b>(§82) — 아래 fixture가 WAITING·EXPIRED를 섞어 두는 이유다.
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
@Tag("mysql")
class BillingSnapshotIntegrationTest {

    private static final String TENANT_KEY = "t_dev_billing";
    private static final String QUEUE_ID = "q_dev_billing";
    /** 두 번째 테넌트는 tester 대역(`*_test_*`)으로 만든다 — 기존 fixture는 backend 소유라 손대지 않는다. */
    private static final String TENANT_KEY_B = "t_test_billing_b";
    private static final String QUEUE_ID_B = "q_test_billing_b";
    /**
     * 🪤 <b>실 데이터가 없는 달로 고정한다.</b> UPSERT는 {@code tokens} 전체를 훑어 대상 월에 토큰이
     * 있는 <b>모든</b> 테넌트 행을 만든다 — 현재월로 바꾸면 남의 청구 스냅샷을 공유 DB에서 덮어쓰고,
     * 아래 정리 로직은 자기 테넌트 것만 지우므로 그 흔적이 남는다.
     */
    private static final YearMonth TARGET = YearMonth.of(2026, 7);

    @Autowired private BillingJdbcAdapter adapter;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;
    private long tenantIdB;

    @BeforeAll
    void seedFixtures() {
        tenantId = seedTenant(TENANT_KEY, "dev_billing@test.local", QUEUE_ID, "dev-billing-queue");
        tenantIdB = seedTenant(TENANT_KEY_B, "test_billing_b@test.local", QUEUE_ID_B, "test-billing-queue-b");
    }

    @AfterEach
    void cleanRows() {
        jdbc.update("DELETE FROM tokens WHERE queue_id IN (?, ?)", QUEUE_ID, QUEUE_ID_B);
        jdbc.update("DELETE FROM billing_snapshots WHERE tenant_id IN (?, ?)", tenantId, tenantIdB);
    }

    @AfterAll
    void cleanupFixtures() {
        jdbc.update("DELETE FROM queues WHERE queue_id IN (?, ?)", QUEUE_ID, QUEUE_ID_B);
        jdbc.update("DELETE FROM tenants WHERE tenant_id IN (?, ?)", TENANT_KEY, TENANT_KEY_B);
    }

    private long seedTenant(String tenantKey, String email, String queueId, String queueName) {
        jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                tenantKey, email, "x", tenantKey);
        long id = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, tenantKey);
        jdbc.update("INSERT IGNORE INTO queues (queue_id, tenant_id, name, max_capacity) VALUES (?, ?, ?, ?)",
                queueId, id, queueName, 100000);
        return id;
    }

    @Test
    @DisplayName("대상 월 안의 토큰만 센다 — 상태는 보지 않고, 경계 밖은 제외한다")
    void countsOnlyTargetMonth() {
        seed("tok_b1", LocalDateTime.of(2026, 7, 1, 0, 0, 0), 0);          // 시작 경계 = 포함
        seed("tok_b2", LocalDateTime.of(2026, 7, 15, 12, 0, 0), 4);        // EXPIRED도 과금 대상
        seed("tok_b3", LocalDateTime.of(2026, 7, 31, 23, 59, 59), 2);      // 끝 경계 = 포함
        seed("tok_b4", LocalDateTime.of(2026, 8, 1, 0, 0, 0), 0);          // 다음 달 = 제외
        seed("tok_b5", LocalDateTime.of(2026, 6, 30, 23, 59, 59), 0);      // 지난 달 = 제외

        adapter.upsertMonthlySnapshot(TARGET);

        assertThat(snapshotCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("재실행은 멱등이다 — 집계값도 updated_at도 안 변한다")
    void rerunIsIdempotent() {
        seed("tok_b6", LocalDateTime.of(2026, 7, 10, 0, 0, 0), 0);
        adapter.upsertMonthlySnapshot(TARGET);
        LocalDateTime firstWrite = updatedAt();

        adapter.upsertMonthlySnapshot(TARGET);

        assertThat(snapshotCount()).isEqualTo(1);
        // 🪤 반환값으로 판정하지 않는다 — Connector/J는 안 바뀐 행도 1로 센다(CLI는 0). 증거는 이쪽이다
        assertThat(updatedAt()).isEqualTo(firstWrite);
    }

    @Test
    @DisplayName("늦게 적재된 토큰은 다음 실행이 흡수한다 — 그래서 전월을 다시 돌린다")
    void lateArrivalIsPickedUp() {
        seed("tok_b7", LocalDateTime.of(2026, 7, 10, 0, 0, 0), 0);
        adapter.upsertMonthlySnapshot(TARGET);
        assertThat(snapshotCount()).isEqualTo(1);

        seed("tok_b8", LocalDateTime.of(2026, 7, 11, 0, 0, 0), 0);

        adapter.upsertMonthlySnapshot(TARGET);
        assertThat(snapshotCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("테넌트가 여럿이어도 각자 자기 수만 받는다 — 재실행(ODKU 경로)에서도 섞이지 않는다")
    void aggregatesEachTenantSeparately() {
        // 기존 3건은 테넌트가 하나뿐이라 "값이 남의 것과 뒤바뀌는" 결함을 구조적으로 못 잡는다.
        // UPSERT는 GROUP BY 결과 여러 행을 한 문장에 밀어 넣고, ODKU에서 서브쿼리 별칭(agg.cnt)을
        // 참조한다 — 별칭이 "그 행의 cnt"가 아니라 엉뚱한 행에 묶이면 청구서가 통째로 바뀐다.
        seed("tok_m1", LocalDateTime.of(2026, 7, 5, 0, 0, 0), 0);
        seed("tok_m2", LocalDateTime.of(2026, 7, 6, 0, 0, 0), 1);
        seed("tok_m3", LocalDateTime.of(2026, 7, 7, 0, 0, 0), 2);
        seed("tok_m4", LocalDateTime.of(2026, 7, 5, 0, 0, 0), 0, QUEUE_ID_B, tenantIdB);

        adapter.upsertMonthlySnapshot(TARGET);   // 전부 INSERT 경로

        assertThat(snapshotCount(tenantId, "202607")).isEqualTo(3);
        assertThat(snapshotCount(tenantIdB, "202607")).isEqualTo(1);

        // 두 번째 실행은 두 행 모두 ODKU(UPDATE) 경로를 탄다. 비대칭적으로 늘려서
        // "값이 서로 바뀌어 들어가는" 실패가 통과로 위장하지 못하게 한다.
        seed("tok_m5", LocalDateTime.of(2026, 7, 8, 0, 0, 0), 0, QUEUE_ID_B, tenantIdB);
        seed("tok_m6", LocalDateTime.of(2026, 7, 9, 0, 0, 0), 0, QUEUE_ID_B, tenantIdB);

        adapter.upsertMonthlySnapshot(TARGET);

        assertThat(snapshotCount(tenantId, "202607")).isEqualTo(3);
        assertThat(snapshotCount(tenantIdB, "202607")).isEqualTo(3);
    }

    @Test
    @DisplayName("대상 월만 갱신한다 — 다른 달 스냅샷은 건드리지 않고, 0건 테넌트는 행 자체가 안 생긴다")
    void touchesOnlyTargetMonthAndSkipsZeroTenants() {
        // 6월 스냅샷을 미리 넣어 둔다. UNIQUE가 (tenant_id, `year_month`)가 아니라 tenant_id 단독으로
        // 잘못 잡히거나 월 파라미터가 안 먹으면, 7월 집계가 6월 청구서를 덮어쓴다.
        jdbc.update("INSERT INTO billing_snapshots (tenant_id, `year_month`, `count`) VALUES (?, ?, ?)",
                tenantId, "202606", 999L);
        seed("tok_z1", LocalDateTime.of(2026, 7, 20, 0, 0, 0), 0);
        // 테넌트 B는 7월에 토큰이 0건이다

        adapter.upsertMonthlySnapshot(TARGET);

        assertThat(snapshotCount(tenantId, "202607")).isEqualTo(1);
        assertThat(snapshotCount(tenantId, "202606")).isEqualTo(999);
        // 🔑 0건 테넌트는 GROUP BY 결과에 아예 없다 → 행이 생기지 않는다(-1 = 행 없음).
        //    조회 측이 "행 없음 = 0건"으로 읽어야 한다는 뜻이다. 0을 기대하면 NPE/404가 난다
        assertThat(snapshotCount(tenantIdB, "202607")).isEqualTo(-1);

        jdbc.update("DELETE FROM billing_snapshots WHERE tenant_id = ? AND `year_month` = ?", tenantId, "202606");
    }

    @Test
    @DisplayName("월말 마지막 밀리초(23:59:59.999)까지 센다 — issued_at이 DATETIME(3)이다")
    void includesLastMillisecondOfMonth() {
        // 기존 경계 fixture는 23:59:59(밀리초 0)라, 상한을 atEndOfMonth().atTime(23,59,59)로
        // 잘못 계산하는 흔한 실수를 통과시킨다. .999가 빠지면 월말 1초분이 통째로 미청구다.
        seed("tok_e1", LocalDateTime.of(2026, 7, 31, 23, 59, 59, 999_000_000), 0);
        seed("tok_e2", LocalDateTime.of(2026, 8, 1, 0, 0, 0, 1_000_000), 0);   // 다음 달 첫 밀리초 = 제외

        adapter.upsertMonthlySnapshot(TARGET);

        assertThat(snapshotCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("batch가 여러 대에서 동시에 돌아도 예외 없이 같은 값에 수렴한다 — ShedLock을 안 쓰는 근거")
    void concurrentRunsConverge() throws Exception {
        // BillingSnapshotJob은 ShedLock 없이 돈다. 근거로 든 "UPSERT가 멱등"은 정확성 얘기지
        // 무충돌 얘기가 아니다 — 같은 행에 ODKU가 동시에 꽂히면 데드락/락 타임아웃이 날 수 있고,
        // 그러면 job의 catch가 그날 집계를 통째로 삼킨다. 그래서 실제로 안 나는지를 확인한다.
        // 스레드는 Virtual Thread다(고정 풀은 출발 신호에서 굶어 교착한다).
        seed("tok_c1", LocalDateTime.of(2026, 7, 3, 0, 0, 0), 0);
        seed("tok_c2", LocalDateTime.of(2026, 7, 4, 0, 0, 0), 0);
        seed("tok_c3", LocalDateTime.of(2026, 7, 3, 0, 0, 0), 0, QUEUE_ID_B, tenantIdB);

        int n = 8;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        adapter.upsertMonthlySnapshot(TARGET);
                    } catch (Throwable t2) {
                        failures.add(t2);
                    }
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }

        assertThat(failures).isEmpty();
        assertThat(snapshotCount(tenantId, "202607")).isEqualTo(2);
        assertThat(snapshotCount(tenantIdB, "202607")).isEqualTo(1);
    }

    private void seed(String tokenId, LocalDateTime issuedAt, int status) {
        seed(tokenId, issuedAt, status, QUEUE_ID, tenantId);
    }

    private void seed(String tokenId, LocalDateTime issuedAt, int status, String queueId, long owner) {
        jdbc.update("""
                INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tokenId, queueId, owner, "0190e2c1-user", 1L, status, issuedAt);
    }

    private long snapshotCount() {
        return snapshotCount(tenantId, "202607");
    }

    /** 행이 없으면 {@code -1}. "행이 아예 안 생긴다"와 "0으로 생긴다"를 구분하기 위해 예외 대신 sentinel을 쓴다. */
    private long snapshotCount(long owner, String yearMonth) {
        List<Long> rows = jdbc.queryForList(
                "SELECT `count` FROM billing_snapshots WHERE tenant_id = ? AND `year_month` = ?",
                Long.class, owner, yearMonth);
        return rows.isEmpty() ? -1L : rows.get(0);
    }

    private LocalDateTime updatedAt() {
        return jdbc.queryForObject(
                "SELECT updated_at FROM billing_snapshots WHERE tenant_id = ? AND `year_month` = ?",
                LocalDateTime.class, tenantId, "202607");
    }
}
