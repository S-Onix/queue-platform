package com.sonix.queue.domain.billing;

import java.time.YearMonth;

/**
 * 과금 집계 포트 (Sprint 9 · {@code doc/schema.sql} Step 2).
 *
 * <p><b>과금 단위는 발급된 {@code tokens} 행 하나다.</b> 상태를 보지 않는다 —
 * WAITING·ADMIT_ISSUED·COMPLETED·EXPIRED 전부 한 건이고, 취소는 개념 자체가 없다(§82).
 * 줄을 세워 준 시점에 이미 서비스를 제공한 것이므로 enqueue 직후 이탈해도 청구 대상이다.
 *
 * <p>그래서 <b>유실이 곧 미청구</b>다. 유령 토큰(Redis엔 있고 DB엔 없음)은 그대로 청구에서 빠진다.
 * {@code ReconcileJob}이 그 수를 <b>탐지</b>하지만 복구는 아직 없다 — 그러니 이 값의 신뢰도는
 * 대사 게이지가 0인지에 달려 있다.
 *
 * <p>반대로 <b>중복 적재는 걱정하지 않아도 된다.</b> {@code uq_tokens_token_id (token_id, issued_at)}와
 * {@code TokenEntity}의 {@code ON DUPLICATE KEY UPDATE token_id = token_id}가 At-Least-Once
 * 재전달을 no-op으로 흡수한다. 여기에 멱등 장치를 또 만들 이유는 없다.
 */
public interface BillingRepository {

    /**
     * 한 달치 {@code tenant}별 토큰 수를 {@code billing_snapshots}에 UPSERT한다.
     *
     * <p><b>멱등하다.</b> 같은 달을 몇 번 돌려도 집계 결과가 그대로 덮어써질 뿐이라,
     * 실패한 주기를 다음 주기가 그냥 다시 가져가면 된다. 값이 바뀌지 않으면 MySQL이
     * 행을 건드리지 않아 {@code updated_at}도 그대로다 — "언제 값이 실제로 변했나"가 남는다.
     *
     * <p><b>월 경계는 UTC다.</b> 파티션 표현식·{@code issued_at} 저장이 전부 UTC이므로
     * 여기만 KST로 자르면 월말 9시간분이 어긋난다(§77 Consequences — 표시·청구 계층에서
     * 변환할지는 미해결).
     *
     * <p><b>반환값이 없다.</b> MySQL이 돌려주는 영향 행 수는 해석할 수 없는 숫자다 —
     * ODKU의 UPDATE는 한 행을 2로 세고, Connector/J는 값이 안 바뀐 행도 1로 센다(어댑터 주석 참조).
     * 성패는 예외 여부로 판정하고, 집계값이 실제로 변했는지는 {@code updated_at}이 증언한다.
     *
     * @throws RuntimeException 대상 월의 파티션이 없으면 {@code ERROR 1735}로 죽는다 — 의도된
     *                          fail-loud다(§83). 파티션 사전 생성 누락을 조용히 넘기지 않는다
     */
    void upsertMonthlySnapshot(YearMonth month);
}
