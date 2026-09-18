package com.sonix.queue.domain.billing;

import java.time.YearMonth;

/**
 * 이유: 과금 집계 포트. <b>과금 단위는 발급된 {@code tokens} 행 하나다</b> — 상태를 보지 않는다.
 * 원인: 줄을 세워 준 시점에 서비스를 제공한 것이라, enqueue 직후 이탈해도 청구 대상이다(§82).
 * 🔴 그래서 <b>유실이 곧 미청구</b>다 — 유령 토큰은 청구에서 빠지고 {@code ReconcileJob} 은 탐지만 한다.
 *    값의 신뢰도는 대사 게이지가 0인지에 달려 있다.
 * 🪤 중복 적재는 {@code uq_tokens_token_id} 의 ODKU no-op 이 흡수한다 — 멱등 장치를 또 만들지 마라.
 *
 * @author sonix
 */
public interface BillingRepository {

    /**
     * 이유: 한 달치 테넌트별 토큰 수를 {@code billing_snapshots} 에 UPSERT 한다.
     * 해결: <b>멱등하다</b> — 덮어쓰기라 실패한 주기를 다음 주기가 가져가면 된다.
     * 🪤 <b>월 경계는 UTC 다</b>(§77) — 로컬 시각으로 자르면 월말 9시간분이 어긋난다.
     * 🪤 <b>반환값이 없다</b> — ODKU 의 UPDATE 는 한 행을 2로 세고 드라이버마다 달라 해석할 수 없다.
     *    성패는 예외 여부로만 판정한다.
     *
     * @author sonix
     * @throws RuntimeException 대상 월 파티션이 없으면 {@code ERROR 1735}. fail-loud 다(§83)
     */
    void upsertMonthlySnapshot(YearMonth month);

    /**
     * 이유: 한 달치를 <b>큐×일</b> 단위로 {@code queue_daily_stats} 에 UPSERT 한다.
     * 문제: {@code tokens} 가 M+2 월에 파티션째 사라지면 "어느 큐에서 얼마나 받았나"를 잃는다.
     *       {@code billing_snapshots} 는 테넌트 합계라 큐가 둘 이상이면 분해가 <b>영구히</b> 불가능하다.
     * 해결: {@code upsertMonthlySnapshot} 과 <b>같은 주기·같은 월</b>로 돌린다 — 그래야 두 표가
     *       서로를 감시하는 등식이 성립한다. 전월도 덮어써야 늦은 admit(가장 오래 기다린 토큰)이 산다.
     * 🪤 대기 시간 기준은 {@code admitted_at} 이다 — {@code completed_at} 엔 Tenant 처리 시간이 섞인다.
     *
     * @author sonix
     */
    void upsertDailyStats(YearMonth month);

    /**
     * 이유: 두 집계표가 어긋난 테넌트 수. <b>0이어야 한다</b>.
     * 원인: 둘 다 같은 파티션을 같은 UTC 경계로 세므로 어긋날 구조적 이유가 없다 — 어긋남이 곧 사고다.
     * 🔑 <b>원본이 살아 있는 동안만 잡을 수 있다</b> — 파티션이 사라지면 어느 쪽이 맞는지 판정 못 한다.
     * 🪤 <b>한쪽에만 있는 테넌트도 센다</b> — JOIN 으로 짜면 누락된 쪽이 0으로 나와 가장 큰 사고가 가장 조용해진다.
     * 🪤 Prometheus 카운터와 대조하지 마라 — 저쪽은 앱 카운터라 발행이 유실되면 어긋나는 게 정상이다.
     *
     * @author sonix
     */
    long countBillingMismatch(YearMonth month);

    /**
     * 그 달 파티션에 남아 있는 토큰 수. 파티션이 이미 없으면 {@code -1}.
     *
     * <p>DROP 전 <b>마지막 안전장치</b>다. {@link #countBillingMismatch}는 두 집계가 나란히
     * 0행이어도 0을 돌려주므로, "원본에 행이 있는데 집계가 비었다"는 원본 쪽 수로만 잡힌다.
     * {@code -1}은 "이미 지운 파티션"이라 0("있는데 비었다")과 구분돼야 한다.
     */
    long countPartitionRows(YearMonth month);

    /** 그 달 {@code queue_daily_stats}의 행 수. {@link #countPartitionRows}와 짝으로 관문 ③을 만든다. */
    long countDailyStatRows(YearMonth month);

    /**
     * 그 달 파티션을 <b>영구 삭제</b>한다. 되돌릴 수 없다.
     *
     * <p>🔴 <b>스스로 아무것도 검사하지 않는다.</b> <b>집계 → 대사 → 원본 대조</b> 순서를 지키는
     * 책임은 {@code BillingSnapshotJob.purgeSettledPartition}에 있다.
     *
     * <p>DDL이라 <b>멱등하지 않다</b> — 이미 없는 파티션은 {@code ERROR 1507}이다. 호출부가
     * {@link #countPartitionRows}로 먼저 확인하고, 경쟁에서 진 인스턴스가 받는 그 에러는 삼킨다.
     */
    void dropPartition(YearMonth month);
}
