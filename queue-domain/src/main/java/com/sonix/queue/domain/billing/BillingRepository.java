package com.sonix.queue.domain.billing;

import java.time.YearMonth;

/**
 * 과금 집계 포트 (Sprint 9 · {@code doc/schema.sql} Step 2).
 *
 * <p><b>과금 단위는 발급된 {@code tokens} 행 하나다.</b> 상태를 보지 않는다 —
 * WAITING·ADMIT_ISSUED·COMPLETED·EXPIRED 전부 한 건이고, 취소는 개념 자체가 없다(§82).
 * 줄을 세워 준 시점에 이미 서비스를 제공한 것이므로 enqueue 직후 이탈해도 청구 대상이다.
 *
 * <p>그래서 <b>유실이 곧 미청구</b>다 — 유령 토큰(Redis엔 있고 DB엔 없음)은 청구에서 빠지고,
 * {@code ReconcileJob}이 탐지만 할 뿐 복구는 없다. 값의 신뢰도는 대사 게이지가 0인지에 달려 있다.
 * 반대로 <b>중복 적재는 {@code uq_tokens_token_id}의 ODKU no-op이 흡수</b>하므로 여기에 멱등
 * 장치를 또 만들지 않는다.
 */
public interface BillingRepository {

    /**
     * 한 달치 {@code tenant}별 토큰 수를 {@code billing_snapshots}에 UPSERT한다.
     *
     * <p><b>멱등하다</b> — 몇 번 돌려도 덮어쓰기라 실패한 주기를 다음 주기가 가져가면 된다.
     * 값이 안 바뀌면 {@code updated_at}도 그대로라 "언제 실제로 변했나"가 남는다.
     *
     * <p>🪤 <b>월 경계는 UTC다.</b> 파티션 표현식·{@code issued_at}이 전부 UTC라 여기만 KST로
     * 자르면 월말 9시간분이 어긋난다(§77).
     *
     * <p><b>반환값이 없다.</b> ODKU의 UPDATE는 한 행을 2로 세고 Connector/J는 안 바뀐 행도 1로
     * 세므로 영향 행 수를 해석할 수 없다. 성패는 예외 여부로만 판정한다.
     *
     * @throws RuntimeException 대상 월의 파티션이 없으면 {@code ERROR 1735}로 죽는다 — 의도된
     *                          fail-loud다(§83). 파티션 사전 생성 누락을 조용히 넘기지 않는다
     */
    void upsertMonthlySnapshot(YearMonth month);

    /**
     * 한 달치를 <b>큐×일</b> 단위로 {@code queue_daily_stats}에 UPSERT한다.
     *
     * <p><b>왜 별도 표인가.</b> {@code tokens}가 M+2월에 파티션째 사라진 뒤 "어느 큐가 어느 날
     * 얼마나 받았나"에 답할 수 있는 표는 여기뿐이다. {@code billing_snapshots}는 tenant×month
     * 합계라 큐가 둘 이상이면 청구액 분해가 <b>영구히</b> 불가능하다.
     *
     * <p><b>{@code upsertMonthlySnapshot}과 같은 주기·같은 월이어야 한다</b> — 그래야
     * {@code SUM(total_enqueued) GROUP BY tenant, month == billing_snapshots.count}가 성립한다.
     * 두 표가 서로를 감시하는 유일한 등식이고, 축이 어긋나면 그 수단이 사라진다.
     *
     * <p><b>덮어쓴다.</b> 전월을 그 달 내내 재집계하지 않으면 늦게 admit된 토큰이 통계에서
     * 통째로 빠지는데, 그게 바로 <b>가장 오래 기다린 토큰</b>이라 이 표의 존재 이유다.
     *
     * <p>🪤 <b>대기 시간 기준은 {@code admitted_at}</b>이다. {@code completed_at}에는 테넌트 내부
     * 처리 시간과 Kafka lag이 섞인다 — {@code issued_at → admitted_at}만 Platform 단독 책임 구간이다.
     *
     * <p>🪤 <b>생존 편향</b>: admit까지 간 토큰만 표본이라 <b>대기가 나쁠수록 지표가 좋아 보인다</b>.
     * 같은 행의 {@code total_admit_issued / total_enqueued}(admit률)를 함께 읽어야 해석이 된다.
     *
     * @throws RuntimeException 대상 월의 파티션이 없으면 {@code ERROR 1735}로 죽는다. 여기선
     *                          fail-loud가 프루닝보다 중요하다 — <b>이미 DROP된 달을 재집계하면
     *                          남은 행만 세어 통계를 조용히 0으로 깎기 때문</b>이다
     */
    void upsertDailyStats(YearMonth month);

    /**
     * 두 집계표가 어긋난 테넌트 수. <b>0이어야 한다.</b>
     *
     * <p>{@code queue_daily_stats}(큐×일)를 테넌트×월로 접으면 {@code billing_snapshots}와
     * <b>정확히 같아야 한다</b> — 둘 다 같은 파티션을, 같은 UTC 경계로, 상태 술어 없이(§82)
     * 세기 때문이다. 어긋날 구조적 이유가 없으므로 <b>어긋남 자체가 사고 신호</b>다.
     *
     * <p><b>원본이 살아 있는 동안만 잡을 수 있다.</b> {@code tokens} 파티션이 사라진 뒤에는
     * 청구액과 그 근거가 달라도 어느 쪽이 맞는지 판정할 방법이 없다.
     *
     * <p>🪤 <b>한쪽에만 있는 테넌트도 센다</b> — JOIN으로 짜면 누락된 쪽이 조용히 빠져 "불일치 0"이
     * 나온다. 가장 큰 사고가 가장 조용해진다.
     *
     * <p>🪤 <b>Prometheus 카운터와 대조하지 마라.</b> 저쪽은 앱 카운터, 이쪽은 {@code tokens} 행이라
     * Kafka 발행이 유실되면 어긋나는 게 정상이다. 경보로 쓰면 끝나지 않는 조사가 시작된다.
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
