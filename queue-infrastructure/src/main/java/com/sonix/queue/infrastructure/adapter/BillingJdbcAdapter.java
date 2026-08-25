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
}
