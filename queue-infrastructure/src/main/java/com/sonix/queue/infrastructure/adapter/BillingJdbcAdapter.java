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
     * {@code doc/schema.sql} Step 2의 집계를 한 문장으로. 집계와 적재가 같은 문장이라
     * 수십만 행을 앱으로 끌어오지 않는다.
     *
     * <p>⚠️ {@code schema.sql}의 예제를 <b>그대로 옮긴 것이 아니다</b> — 그쪽은 실행하면 죽는다.
     * 아래 세 곳이 다르고, 셋 다 이유가 있다.
     *
     * <p>🔴 <b>{@code `year_month`}의 백틱은 장식이 아니다.</b> {@code YEAR_MONTH}는 MySQL
     * 예약어(INTERVAL 단위)라 백틱 없이 쓰면 {@code ERROR 1064}다. 컬럼 <b>정의</b> 자리에서는
     * 통과해서 {@code CREATE TABLE}은 멀쩡히 성공한다 — 그래서 스키마만 보면 안 보인다.
     *
     * <p>🔴 <b>{@code INSERT ... SELECT}에는 행 별칭({@code AS new})을 못 붙인다.</b>
     * {@code TokenJpaAdapter}가 deprecated {@code VALUES(col)}을 피하려고 쓰는 그 문법인데,
     * 여기서는 {@code ERROR 1064}다(실측). 대신 <b>SELECT 쪽을 서브쿼리로 감싸 별칭</b>을 주고
     * ODKU에서 그 별칭을 참조한다 — 경고 없이 같은 목적을 달성한다.
     *
     * <p>🔴 <b>{@code updated_at = NOW(3)}을 쓰지 않는다.</b> {@code NOW()}는 세션 TZ를 따르는데
     * {@code mysql} CLI 세션은 KST라 UTC 컬럼에 KST가 들어간다({@code schema.sql}의 [시각 규약]이
     * 경고하는 그 함정). SET 절에서 빼면 {@code ON UPDATE CURRENT_TIMESTAMP(3)}이 <b>값이 실제로
     * 바뀔 때만</b> 찍어 주므로, 재실행해도 "마지막으로 금액이 변한 시각"이 보존된다.
     *
     * <p><b>{@code PARTITION (pYYYY_MM)}은 §83이 확정한 결정이다.</b> {@code PARTITION BY RANGE
     * (YEAR(c)*100 + MONTH(c))}는 옵티마이저가 단조성을 증명하지 못해 <b>범위 조건으로는 프루닝이
     * 안 된다</b> — 13개 파티션을 전부 스캔한다(§83 실측). §83이 {@code RANGE COLUMNS} 재구축안을
     * 기각한 근거가 "집계 배치는 대상 월을 알고 있으니 이 절로 공짜로 얻는다"였다.
     *
     * <p>🪤 대가는 <b>fail-loud</b>다. 미생성 파티션을 지목하면 {@code ERROR 1735}로 죽는다 —
     * 범위 조건이라면 {@code p_future}로 조용히 성공했을 자리다. 파티션 사전 생성 누락을
     * 청구서가 나온 뒤에 아는 것보다 그날 죽는 게 낫다는 판단이다.
     *
     * <p>🪤 <b>파티션 이름은 바인딩 파라미터로 못 넣는다</b>(식별자 자리다). {@code YearMonth}에서
     * 포맷한 값이라 외부 입력이 닿지 않는다.
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
     * {@code doc/schema.sql} Step 1의 집계. {@code UPSERT_MONTHLY}와 같은 파티션을 훑으므로
     * 바로 뒤에 붙여 돌리면 버퍼풀이 따뜻하다(실측 180ms / 16만 행).
     *
     * <p>🔴 <b>{@code ON DUPLICATE KEY UPDATE id = id}로 두면 안 된다.</b> {@code schema.sql}의
     * 원안이 그랬고, 그러면 <b>늦게 도착한 admit이 영원히 반영되지 않는다</b>(도커 실증:
     * 재집계해도 {@code total_admit_issued}가 0에 고정). 오래 기다린 사람일수록 늦게 admit되므로,
     * 하필 이 표가 남기려던 것만 골라서 버린다. 전 컬럼을 덮어쓴다.
     *
     * <p>🔴 <b>{@code SUM(admitted_at IS NOT NULL)}이지 {@code SUM(status = 1)}이 아니다.</b>
     * {@code ReconcileJob}이 잔류 {@code ADMIT_ISSUED}를 {@code status = 4}로 정리하므로
     * 후자는 0이 나온다 — 실측에서 15,151건이 {@code status = 4} 아래 숨어 있었다.
     * 그래서 {@code status}(집합을 분할)와 {@code admitted_at}(그 분할을 가로지름)이 둘 다 필요하다.
     * 덕분에 {@code total_admit_issued - total_completed} = "입장권 받고 안 들어온 수"가 공짜로 나온다.
     *
     * <p>🔴 <b>{@code AVG}가 아니라 {@code SUM}이다.</b> 평균은 합산되지 않는다 — 일별 AVG로는
     * 월 평균을 만들 수 없다(각 날의 표본 수를 모르면 가중을 못 준다). 분모는 어차피 저장하는
     * {@code total_admit_issued}가 갖고 있다. 같은 이유로 {@code p50}/{@code p99}는 컬럼으로
     * 두지 않는다 — 백분위는 원리적으로 합산도 재계산도 안 된다.
     *
     * <p>🪤 {@code stat_date = DATE(issued_at)}은 <b>"줄 선 날"</b> 기준이라, 4/30 발행 · 5/1 입장인
     * 토큰의 대기 시간은 4/30에 붙는다. {@code admitted_at} 기준으로 바꾸면 안 되는 이유는
     * 취향이 아니다 — <b>한 토큰 = 한 파티션 = 한 stat 행</b>이 깨져 재집계가 멱등하지 않게 되고,
     * {@code PARTITION} 절도 못 쓰게 된다. 밀림은 {@code waitingTtl} 7200초가 상한이다.
     */
    private static final String UPSERT_DAILY_STATS = """
            INSERT INTO queue_daily_stats
                (tenant_id, queue_id, stat_date,
                 total_enqueued, total_completed, total_expired,
                 total_admit_issued, sum_wait_sec, max_wait_sec)
            SELECT a.tenant_id, a.queue_id, a.stat_date, a.enq, a.cmp, a.exp, a.adm, a.sw, a.mw
              FROM (SELECT tenant_id, queue_id,
                           DATE(issued_at)                                    AS stat_date,
                           COUNT(*)                                           AS enq,
                           SUM(status = 2)                                    AS cmp,
                           SUM(status = 4)                                    AS exp,
                           SUM(admitted_at IS NOT NULL)                       AS adm,
                           SUM(TIMESTAMPDIFF(SECOND, issued_at, admitted_at)) AS sw,
                           MAX(TIMESTAMPDIFF(SECOND, issued_at, admitted_at)) AS mw
                      FROM tokens PARTITION (%s)
                     WHERE issued_at >= ? AND issued_at < ?
                     GROUP BY tenant_id, queue_id, DATE(issued_at)) AS a
            ON DUPLICATE KEY UPDATE
                total_enqueued     = a.enq, total_completed    = a.cmp,
                total_expired      = a.exp, total_admit_issued = a.adm,
                sum_wait_sec       = a.sw,  max_wait_sec       = a.mw
            """;

    private final JdbcTemplate jdbcTemplate;

    public BillingJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 🔴 <b>{@code READ COMMITTED}가 아니면 이 문장이 {@code tokens} 적재를 막는다.</b>
     * REPEATABLE READ에서 {@code INSERT ... SELECT}는 source 행에 <b>shared next-key lock</b>을
     * 걸고, 그 사이 같은 구간으로 들어오는 INSERT가 대기한다. 실측: 집계 트랜잭션이 열려 있는 동안
     * {@code tokens} INSERT가 <b>6초 대기 후 {@code ERROR 1205}</b>로 죽었고,
     * {@code READ COMMITTED}에서는 <b>0.033초</b>에 통과했다.
     *
     * <p>당월 집계는 <b>컨슈머가 지금 쓰고 있는 바로 그 파티션</b>을 훑으므로 {@code PARTITION} 절로는
     * 안 풀린다 — 두 조치는 겹치지 않는다. 막히면 {@code queue-consumer}의 적재가 밀리고,
     * 그건 Kafka lag → {@code ReconcileJob}의 정착 판정 오염으로 이어진다.
     *
     * <p>격리수준을 이 메서드에만 건다. 레포 전체엔 격리수준 설정이 없어 MySQL 기본
     * REPEATABLE READ이고, 그 기본값에 기대는 다른 경로를 건드리지 않기 위해서다.
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
     * 🔴 <b>{@code @Transactional(readOnly = true)}를 붙이면 안 된다.</b>
     * {@code ReplicationRoutingDataSource}가 그걸 보고 <b>replica로 보낸다</b>(prod는 별도 호스트).
     * 이 조회는 <b>방금 master에 커밋한 두 표</b>를 대조하는 것이라, 복제가 한쪽만 따라잡은 창에
     * 걸리면 그 달 토큰이 있는 거의 모든 테넌트가 불일치로 잡힌다.
     *
     * <p>트랜잭션 자체를 안 건다 — 단문 {@code SELECT}라 필요가 없고, 트랜잭션이 없으면
     * 라우팅 키가 {@code "master"}가 되어 원하는 쪽으로 간다. 하루 한 번이라 비용도 문제가 아니다.
     *
     * <p>🪤 <b>통합 테스트로는 이 결함을 못 잡는다</b> — 테스트 설정이 replica url을 master(3306)로
     * 준다. 라우팅이 갈라지지 않으므로 어떤 단정도 빨개지지 않는다.
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
     * 🔴 <b>되돌릴 수 없다.</b> 호출 조건은 포트 javadoc 참조.
     *
     * <p>{@code @Transactional}을 걸지 않는다 — DDL은 MySQL에서 <b>암묵적 커밋</b>이라 트랜잭션이
     * 아무것도 보호하지 못한다. 걸어 두면 "롤백되겠지"라는 잘못된 안심만 준다.
     */
    @Override
    public void dropPartition(YearMonth month) {
        jdbcTemplate.execute(
                "ALTER TABLE tokens DROP PARTITION %s".formatted(month.format(PARTITION_NAME)));
    }
}
