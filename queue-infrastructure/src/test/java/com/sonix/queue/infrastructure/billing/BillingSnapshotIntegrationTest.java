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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        jdbc.update("DELETE FROM queue_daily_stats WHERE queue_id IN (?, ?)", QUEUE_ID, QUEUE_ID_B);
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

    // ────────────────────────── queue_daily_stats (§86) ──────────────────────────

    @Test
    @DisplayName("입장권 수는 status가 아니라 admitted_at으로 센다 — status=1은 ReconcileJob이 4로 지워 버린다")
    void admitIssuedComesFromAdmittedAtNotStatus() {
        // 🔑 이 테스트가 잡는 결함: SUM(status = 1)로 짜면 실측 15,151건이 통째로 0이 된다.
        //    ReconcileJob이 complete 창(300초)을 넘긴 ADMIT_ISSUED 잔류를 status=4로 정리하기 때문에
        //    "입장권을 받았다"는 사실은 status가 아니라 admitted_at에만 남는다.
        // 🔑 tok_d1(status=4 + admitted_at 있음)이 이 방어를 혼자 진다. 지우면
        //    SUM(status IN (1,2)) 같은 변이가 나머지 어디에서도 안 잡힌다
        seedAdmitted("tok_d1", ldt(7, 5, 10), ldt(7, 5, 11), 4);   // 입장권 받고 만료 = 둘 다 잡혀야 한다
        seedAdmitted("tok_d2", ldt(7, 5, 10), ldt(7, 5, 12), 2);   // 입장권 받고 완료
        seed("tok_d3", ldt(7, 5, 10), 4);                          // 뽑히기 전에 만료

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_admit_issued", QUEUE_ID, "2026-07-05")).isEqualTo(2);
        assertThat(stat("total_expired", QUEUE_ID, "2026-07-05")).isEqualTo(2);
        assertThat(stat("total_completed", QUEUE_ID, "2026-07-05")).isEqualTo(1);
        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-05")).isEqualTo(3);
        // 🔑 이 그룹만이 admit 수(2) ≠ 행 수(3)다. 여기서 SUM을 단정하지 않으면
        //    SUM(...)을 AVG(...) * COUNT(*)로 바꿔도 14건 전부 통과한다(90×3=270 vs 정답 180)
        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-05")).isEqualTo(180);
        // 🔑 합이 안 맞는 게 정상이다. total_admit_issued는 상태가 아니라 "사건" 카운터라
        //    total_expired와 겹친다. enqueued = admitted + completed + expired 가 아니다
    }

    @Test
    @DisplayName("대기 시간은 AVG가 아니라 SUM으로 남긴다 — 평균은 여러 날을 다시 합칠 수 없다")
    void storesSumNotAverage() {
        seedAdmitted("tok_d4", ldt(7, 6, 0), ldt(7, 6, 10), 2);    // 600초
        seedAdmitted("tok_d5", ldt(7, 6, 0), ldt(7, 6, 30), 2);    // 1800초

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-06")).isEqualTo(2400);
        assertThat(stat("max_wait_sec", QUEUE_ID, "2026-07-06")).isEqualTo(1800);
        // AVG였다면 1200이 남고, 분모(2)를 모르면 다른 날과 합칠 때 가중을 못 준다
    }

    @Test
    @DisplayName("admit이 0건이면 대기 시간은 NULL이다 — \"즉시 입장(0초)\"과 구분돼야 한다")
    void zeroAdmitsLeaveWaitNull() {
        seed("tok_d6", ldt(7, 7, 0), 4);

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_admit_issued", QUEUE_ID, "2026-07-07")).isZero();
        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-07")).isEqualTo(-1);   // -1 = NULL sentinel
        assertThat(stat("max_wait_sec", QUEUE_ID, "2026-07-07")).isEqualTo(-1);
    }

    @Test
    @DisplayName("재집계가 늦게 붙은 admit을 반영한다 — ODKU가 id = id면 영원히 안 들어온다")
    void reaggregationPicksUpLateAdmits() {
        // 🔑 schema.sql 원안의 `ON DUPLICATE KEY UPDATE id = id`가 정확히 여기서 죽는다.
        //    그리고 늦게 admit되는 토큰이 곧 가장 오래 기다린 토큰이라,
        //    하필 이 표가 남기려던 것만 골라서 버린다.
        seed("tok_d7", ldt(7, 8, 0), 0);
        adapter.upsertDailyStats(TARGET);
        assertThat(stat("total_admit_issued", QUEUE_ID, "2026-07-08")).isZero();

        jdbc.update("UPDATE tokens SET admitted_at = ?, status = 2 WHERE token_id = ?",
                ldt(7, 8, 45), "tok_d7");
        // 🔑 행도 함께 늘린다. 안 늘리면 나머지 4컬럼이 우연히 같은 값이라
        //    ODKU 목록에서 그것들을 빼도(= id = id와 같은 결함) 테스트가 통과한다
        seedAdmitted("tok_d7b", ldt(7, 8, 0), ldt(7, 8, 60), 4);

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_admit_issued", QUEUE_ID, "2026-07-08")).isEqualTo(2);
        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-08")).isEqualTo(6300);   // 2700 + 3600
        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-08")).isEqualTo(2);
        assertThat(stat("total_completed", QUEUE_ID, "2026-07-08")).isEqualTo(1);
        assertThat(stat("total_expired", QUEUE_ID, "2026-07-08")).isEqualTo(1);
        assertThat(stat("max_wait_sec", QUEUE_ID, "2026-07-08")).isEqualTo(3600);
        assertThat(rowCount(QUEUE_ID)).isEqualTo(1);   // UPDATE지 두 번째 INSERT가 아니다
    }

    @Test
    @DisplayName("귀속일은 줄 선 날이다 — 날을 넘겨 입장해도 대기 시간은 발행일 행에 붙는다")
    void attributedToIssuedDateNotAdmitDate() {
        // 🔑 admitted_at 기준으로 귀속하면 "한 토큰 = 한 파티션 = 한 stat 행"이 깨진다.
        //    7/31 발행 · 8/1 입장이면 8월 행이 생기고, 8월 집계가 그 키를 덮어쓰며
        //    7월 파티션에서 온 몫을 지운다 — 그때 7월 파티션은 이미 DROP돼 있을 수 있다.
        seedAdmitted("tok_d8", ldt(7, 9, 23 * 60 + 50), ldt(7, 10, 10), 2);   // 7/9 23:50 → 7/10 00:10

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-09")).isEqualTo(1);
        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-09")).isEqualTo(1200);
        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-10")).isEqualTo(-1);   // 행 자체가 없다
    }

    @Test
    @DisplayName("큐가 여럿이면 큐마다 나뉜다 — billing이 영원히 답할 수 없는 바로 그 분해다")
    void splitsByQueue() {
        seed("tok_d9", ldt(7, 11, 0), 0);
        seed("tok_d10", ldt(7, 11, 0), 0, QUEUE_ID_B, tenantIdB);

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-11")).isEqualTo(1);
        assertThat(stat("total_enqueued", QUEUE_ID_B, "2026-07-11")).isEqualTo(1);

        // 2회차는 두 행 모두 ODKU(UPDATE) 경로다. 비대칭으로 늘려서
        // "파생 별칭이 엉뚱한 행에 묶이는" 결함이 통과로 위장하지 못하게 한다.
        // 월별 쪽 aggregatesEachTenantSeparately와 같은 이유이고, 큐×일은 그룹이 훨씬 많다
        seed("tok_d10b", ldt(7, 11, 0), 0, QUEUE_ID_B, tenantIdB);
        seed("tok_d10c", ldt(7, 11, 0), 0, QUEUE_ID_B, tenantIdB);

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_enqueued", QUEUE_ID, "2026-07-11")).isEqualTo(1);
        assertThat(stat("total_enqueued", QUEUE_ID_B, "2026-07-11")).isEqualTo(3);
    }

    @Test
    @DisplayName("만료 사유별로 나눠 센다 — 셋의 의미가 정반대라 총계로는 조치가 안 나온다")
    void splitsExpiredByReason() {
        seedExpired("tok_d15", ldt(7, 15, 0), 3);   // INACTIVE   = 정상 이탈
        seedExpired("tok_d16", ldt(7, 15, 0), 4);   // WAITING_TTL = 용량 부족
        seedExpired("tok_d17", ldt(7, 15, 0), 4);
        seedExpired("tok_d18", ldt(7, 15, 0), 2);   // ADMIT_STALE = Tenant 귀책
        seed("tok_d19", ldt(7, 15, 0), 4);          // 사유 없는 옛 행 → 어느 칸에도 안 들어간다

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("total_expired", QUEUE_ID, "2026-07-15")).isEqualTo(5);
        assertThat(stat("expired_inactive", QUEUE_ID, "2026-07-15")).isEqualTo(1);
        assertThat(stat("expired_waiting_ttl", QUEUE_ID, "2026-07-15")).isEqualTo(2);
        assertThat(stat("expired_admit_stale", QUEUE_ID, "2026-07-15")).isEqualTo(1);
        // 🔑 합(4) ≠ total_expired(5). 사유가 없던 시기의 행이 NULL이라 그렇고, 그 차이가
        //    "언제부터 사유를 남기기 시작했나"다. 억지로 맞추면 그 정보가 사라진다
    }

    private void seedExpired(String tokenId, LocalDateTime issuedAt, int reason) {
        seed(tokenId, issuedAt, 4);
        jdbc.update("UPDATE tokens SET expired_reason = ? WHERE token_id = ?", reason, tokenId);
    }

    @Test
    @DisplayName("음수 대기를 그대로 보존한다 — GREATEST(...,0)으로 가리면 시계 스큐 신호가 사라진다")
    void preservesNegativeWaitFromClockSkew() {
        // 🔑 issued_at·admitted_at 둘 다 앱 시계라 API 서버 N대의 스큐만큼 음수가 나온다
        //    (로컬 실 데이터에 -398초가 실재한다). schema.sql이 "가리지 않는다"를 결정으로
        //    못박았는데 이 테스트가 없으면 누가 GREATEST를 넣어도 아무것도 안 깨진다
        seedAdmitted("tok_d13", ldt(7, 13, 10), ldt(7, 13, 5), 4);

        adapter.upsertDailyStats(TARGET);

        assertThat(stat("max_wait_sec", QUEUE_ID, "2026-07-13")).isEqualTo(-300);
        assertThat(stat("sum_wait_sec", QUEUE_ID, "2026-07-13")).isEqualTo(-300);
    }

    @Test
    @DisplayName("없는 파티션을 지목하면 죽는다 — 조용히 성공하면 DROP된 달의 통계가 0으로 깎인다")
    void failsLoudOnMissingPartition() {
        // 🔑 PARTITION (%s) 절을 지워도 나머지 테스트는 전부 통과한다. 그 절의 존재 이유
        //    ("DROP된 달을 재집계하면 남은 행만 세어 조용히 0으로 깎는다")를 못 박는 유일한 테스트다.
        //    schema.sql의 파티션은 p2026_01부터라 2025-01은 존재하지 않는다
        assertThatThrownBy(() -> adapter.upsertDailyStats(YearMonth.of(2025, 1)))
                .hasMessageContaining("p2025_01");
    }

    @Test
    @DisplayName("파티션 존재 여부와 원본 건수를 구분해 돌려준다 — DROP 전 마지막 관문이다")
    void reportsPartitionRowsAndAbsence() {
        seed("tok_d14", ldt(7, 14, 0), 0);

        assertThat(adapter.countPartitionRows(YearMonth.of(2025, 1))).isEqualTo(-1);  // 파티션 없음
        assertThat(adapter.countPartitionRows(TARGET)).isEqualTo(1);                  // 있고, 1건
    }

    @Test
    @DisplayName("대사는 0이다. 그리고 한쪽 표가 통째로 비어도 잡는다 — JOIN이면 조용히 0이 나올 자리다")
    void mismatchDetectsOneSidedLoss() {
        seed("tok_d11", ldt(7, 12, 0), 0);
        seed("tok_d12", ldt(7, 12, 0), 0, QUEUE_ID_B, tenantIdB);
        adapter.upsertMonthlySnapshot(TARGET);
        adapter.upsertDailyStats(TARGET);

        // 🪤 이 쿼리는 테넌트 필터가 없는 전역 집계다. 공유 DB에 남의 7월 데이터가 있으면
        //    절대값 단정이 무관한 이유로 깨진다 — baseline 대비 증가분으로 본다
        long baseline = adapter.countBillingMismatch(TARGET);
        assertThat(baseline).isZero();

        // ① 청구액만 어긋남
        jdbc.update("UPDATE billing_snapshots SET `count` = `count` + 1 WHERE tenant_id = ?", tenantId);
        assertThat(adapter.countBillingMismatch(TARGET)).isEqualTo(baseline + 1);

        // ② 근거 쪽이 통째로 소실 — JOIN으로 짰다면 이 테넌트가 결과에서 빠져 안 잡힌다
        jdbc.update("DELETE FROM queue_daily_stats WHERE queue_id = ?", QUEUE_ID_B);
        assertThat(adapter.countBillingMismatch(TARGET)).isEqualTo(baseline + 2);
    }

    /** {@code TARGET}(2026-07) 안의 시각. {@code minuteOfDay}로 시분을 준다. */
    private static LocalDateTime ldt(int month, int day, int minuteOfDay) {
        return LocalDateTime.of(2026, month, day, minuteOfDay / 60, minuteOfDay % 60);
    }

    private void seedAdmitted(String tokenId, LocalDateTime issuedAt, LocalDateTime admittedAt, int status) {
        seed(tokenId, issuedAt, status);
        jdbc.update("UPDATE tokens SET admitted_at = ? WHERE token_id = ?", admittedAt, tokenId);
    }

    /** 행이 없거나 값이 NULL이면 {@code -1}. "행 없음"과 "0"을 구분해야 하는 컬럼들이라 sentinel을 쓴다. */
    private long stat(String column, String queueId, String statDate) {
        List<Long> rows = jdbc.queryForList(
                "SELECT " + column + " FROM queue_daily_stats WHERE queue_id = ? AND stat_date = ?",
                Long.class, queueId, statDate);
        return rows.isEmpty() || rows.get(0) == null ? -1L : rows.get(0);
    }

    private long rowCount(String queueId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_daily_stats WHERE queue_id = ?", Long.class, queueId);
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
