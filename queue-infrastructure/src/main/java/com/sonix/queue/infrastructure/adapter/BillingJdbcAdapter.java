package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.billing.BillingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

@Repository
public class BillingJdbcAdapter implements BillingRepository {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    /** 파티션 이름 규약은 {@code doc/schema.sql}의 {@code PARTITION p2026_04 VALUES LESS THAN ...}이다. */
    private static final DateTimeFormatter PARTITION_NAME = DateTimeFormatter.ofPattern("'p'yyyy'_'MM");

    /**
     * 이유: 월별 과금 집계를 <b>한 문장</b>으로 한다 — 수십만 행을 앱으로 끌어오지 않는다.
     * ⚠️ {@code schema.sql} 예제를 그대로 옮기면 <b>죽는다</b>: ①{@code `year_month`} 백틱(예약어인데
     *    <b>컬럼 정의 자리에선 통과</b>한다) ②{@code AS new} 불가 → 서브쿼리 ③{@code NOW()} 는 세션 TZ
     * 🔑 {@code PARTITION (pYYYY_MM)} 은 §83 — 범위 조건으로는 프루닝이 안 돼 13개를 전부 스캔한다(실측).
     * 🪤 대가는 <b>fail-loud</b> — 미생성 파티션이면 {@code ERROR 1735} 다. 청구서가 나온 뒤 아는 것보다 낫다.
     */
    private static final String UPSERT_MONTHLY = """
            INSERT INTO billing_snapshots (tenant_id, `year_month`, `count`)
            SELECT agg.tenant_id, ?, agg.cnt
              FROM (SELECT tenant_id, COUNT(*) AS cnt
                      FROM tokens PARTITION (%s)
                     WHERE issued_at >= ? AND issued_at < ?
                     GROUP BY tenant_id) AS agg
            ON DUPLICATE KEY UPDATE `count` = agg.cnt
            """;

    /**
     * 이유: 큐×일 집계. {@code UPSERT_MONTHLY} 바로 뒤에 돌리면 버퍼풀이 따뜻하다(실측 180ms / 16만 행).
     * 🔴 <b>{@code ODKU id = id} 로 두지 마라</b> — 멱등이 아니라 <b>불변</b>이 되어 늦은 admit 이 영원히 반영되지 않는다. <b>오래 기다린 사람일수록 늦게 붙어</b> 이 표가 남기려던 것만 버린다.
     * 🔴 <b>{@code SUM(admitted_at IS NOT NULL)} 이다</b> — {@code status = 1} 로 세면 0 이다(대사가 잔류를 4로 정리. 실측 15,151건). <b>{@code AVG} 도 금지</b>.
     * 🪤 <b>{@code =} 가 아니라 {@code <=>}</b> — NULL 허용 컬럼이라 전 행이 NULL 이면 SUM 이 NULL 을
     *    돌려주고 {@code NOT NULL} 컬럼에 적재가 통째로 실패한다(실측 8건).
     */
    private static final String UPSERT_DAILY_STATS = """
            INSERT INTO queue_daily_stats
                (tenant_id, queue_id, stat_date,
                 total_enqueued, total_completed, total_expired,
                 expired_admit_stale, expired_inactive, expired_waiting_ttl,
                 total_admit_issued, sum_wait_sec, max_wait_sec)
            SELECT a.tenant_id, a.queue_id, a.stat_date, a.enq, a.cmp, a.exp,
                   a.e_stale, a.e_inact, a.e_wait, a.adm, a.sw, a.mw
              FROM (SELECT tenant_id, queue_id,
                           DATE(issued_at)                                    AS stat_date,
                           COUNT(*)                                           AS enq,
                           SUM(status = 2)                                    AS cmp,
                           SUM(status = 4)                                    AS exp,
                           SUM(expired_reason <=> 2)                            AS e_stale,
                           SUM(expired_reason <=> 3)                            AS e_inact,
                           SUM(expired_reason <=> 4)                            AS e_wait,
                           SUM(admitted_at IS NOT NULL)                       AS adm,
                           SUM(TIMESTAMPDIFF(SECOND, issued_at, admitted_at)) AS sw,
                           MAX(TIMESTAMPDIFF(SECOND, issued_at, admitted_at)) AS mw
                      FROM tokens PARTITION (%s)
                     WHERE issued_at >= ? AND issued_at < ?
                     GROUP BY tenant_id, queue_id, DATE(issued_at)) AS a
            ON DUPLICATE KEY UPDATE
                total_enqueued     = a.enq, total_completed    = a.cmp,
                total_expired      = a.exp, total_admit_issued = a.adm,
                expired_admit_stale = a.e_stale, expired_inactive = a.e_inact,
                expired_waiting_ttl = a.e_wait,
                sum_wait_sec       = a.sw,  max_wait_sec       = a.mw
            """;

    /**
     * {@code DROP PARTITION}이 MDL을 기다릴 최대 시간. 못 잡으면 {@code ERROR 1205}로 포기한다.
     * 짧을수록 좋다 — 이 값이 곧 <b>다른 요청이 막힐 수 있는 시간의 상한</b>이다.
     */
    private static final int DROP_LOCK_WAIT_SECONDS = 3;

    private final JdbcTemplate jdbcTemplate;

    public BillingJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 이유: 🔴 <b>{@code READ COMMITTED} 가 아니면 이 문장이 {@code tokens} 적재를 막는다.</b>
     * 원인: REPEATABLE READ 에서 {@code INSERT ... SELECT} 는 source 행에 <b>shared next-key lock</b> 을 걸어
     *       같은 구간의 INSERT 가 대기한다(실측: 6초 대기 후 {@code ERROR 1205} → RC 에서는 0.033초).
     * 해결: 격리수준을 <b>이 메서드에만</b> 건다 — 레포 전체엔 설정이 없어 기본값에 기대는 경로를 안 건드린다.
     * 🪤 {@code PARTITION} 절로는 안 풀린다 — 당월 집계는 <b>컨슈머가 지금 쓰는 그 파티션</b>을 훑는다.
     *
     * @author sonix
     */
   @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void upsertMonthlySnapshot(YearMonth month) {
        jdbcTemplate.update(
                UPSERT_MONTHLY.formatted(month.format(PARTITION_NAME)),
                month.format(YYYYMM),
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
    }

    /**
     * 🪤 <b>{@code JOIN}이 아니라 {@code UNION ALL} + {@code GROUP BY}인 이유</b>: JOIN이면 한쪽에만
     * 있는 테넌트가 결과에서 통째로 빠져 "불일치 0"이 된다. 한쪽 표가 아예 비어 있는 것이
     * 가장 큰 사고인데 그게 가장 조용해진다. 양쪽을 0으로 채워 합치면 그 경우가 차이로 드러난다.
     *
     * <p>🪤 {@code stat_date}로 자르는 범위는 {@code UTC} 월 경계다 — {@code billing_snapshots}의
     * {@code year_month}와 같은 축이어야 등식이 성립한다.
     */
    private static final String COUNT_MISMATCH = """
            SELECT COUNT(*) FROM (
              SELECT u.tenant_id FROM (
                SELECT tenant_id, total_enqueued AS d, 0 AS b
                  FROM queue_daily_stats WHERE stat_date >= ? AND stat_date < ?
                UNION ALL
                SELECT tenant_id, 0, `count`
                  FROM billing_snapshots WHERE `year_month` = ?
              ) AS u
              GROUP BY u.tenant_id HAVING SUM(u.d) <> SUM(u.b)
            ) AS x
            """;

    /**
     * {@code upsertMonthlySnapshot}과 <b>같은 이유로</b> {@code READ COMMITTED}다 —
     * 같은 파티션을 같은 방식으로 훑으므로 REPEATABLE READ면 컨슈머의 {@code tokens} 적재를
     * 똑같이 막는다. 오히려 이쪽이 뒤에 도므로 창이 더 길다.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void upsertDailyStats(YearMonth month) {
        jdbcTemplate.update(
                UPSERT_DAILY_STATS.formatted(month.format(PARTITION_NAME)),
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
    }

    /**
     * 이유: 두 집계표를 대조한다. 🔴 <b>{@code @Transactional(readOnly = true)} 를 붙이면 안 된다.</b>
     * 원인: 그걸 보고 <b>replica 로 보내는데</b> 이 조회는 <b>방금 master 에 커밋한 두 표</b>를 대조한다 —
     *       복제가 한쪽만 따라잡은 창에 걸리면 거의 모든 테넌트가 불일치로 잡힌다.
     * 해결: 트랜잭션 자체를 걸지 않는다 — 없으면 라우팅 키가 master 다(§4-3). 하루 한 번이라 비용도 없다.
     * 🪤 <b>통합 테스트로는 못 잡는다</b> — 테스트가 replica url 을 master 로 줘 라우팅이 갈라지지 않는다.
     *
     * @author sonix
     */
   @Override
    public long countBillingMismatch(YearMonth month) {
        Long n = jdbcTemplate.queryForObject(COUNT_MISMATCH, Long.class,
                month.atDay(1), month.plusMonths(1).atDay(1), month.format(YYYYMM));
        return n == null ? 0L : n;
    }

    /**
     * 🪤 <b>{@code information_schema}로 존재를 먼저 본다.</b> 없는 파티션을 {@code PARTITION} 절로
     * 지목하면 {@code ERROR 1735}라, 존재 확인과 건수 조회를 한 문장으로 합칠 수 없다.
     */
    @Override
    public long countPartitionRows(YearMonth month) {
        String partition = month.format(PARTITION_NAME);
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.PARTITIONS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tokens' AND PARTITION_NAME = ?
                """, Integer.class, partition);
        if (exists == null || exists == 0) {
            return -1L;
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tokens PARTITION (%s)".formatted(partition), Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public long countDailyStatRows(YearMonth month) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM queue_daily_stats WHERE stat_date >= ? AND stat_date < ?",
                Long.class, month.atDay(1), month.plusMonths(1).atDay(1));
        return n == null ? 0L : n;
    }

    /**
     * 이유: 파티션을 지운다. 🔴 <b>되돌릴 수 없다</b>(호출 조건은 포트 javadoc).
     * 문제: {@code DROP PARTITION} 은 <b>테이블 전체에 배타적 MDL</b> 을 잡아, 긴 트랜잭션이 물고 있으면 <b>그 뒤에 도착한 평범한 INSERT 가 전부 줄을 선다</b>(실측 3.05초 블록).
     * 원인·해결: {@code lock_wait_timeout} 기본값이 <b>365일</b>이라 짧게 걸어 즉시 포기시킨다
     *       (3.05 → 1.04초) — <b>지연은 공짜고 블로킹은 사고다</b>.
     * 🪤 세션 변수라 <b>커넥션 풀에 남는다</b> — 원복까지 같은 {@code execute} 안에서 한다.
     *
     * @author sonix
     */
    @Override
    public void dropPartition(YearMonth month) {
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("SET SESSION lock_wait_timeout = " + DROP_LOCK_WAIT_SECONDS);
                try {
                    st.execute("ALTER TABLE tokens DROP PARTITION %s"
                            .formatted(month.format(PARTITION_NAME)));
                } finally {
                    st.execute("SET SESSION lock_wait_timeout = @@GLOBAL.lock_wait_timeout");
                }
            }
            return null;
        });
    }
}
