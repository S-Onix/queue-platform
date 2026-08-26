package com.sonix.queue.domain.queue;

/**
 * 토큰이 <b>왜</b> 만료됐는가. {@code tokens.expired_reason}(TINYINT)에 저장한다.
 *
 * <p><b>넷 다 Platform이 자기 시계와 자기 키로 혼자 판정한 것이다</b> — 테넌트가 알려줄 것이 없다.
 * 테넌트만 아는 것은 따로 있지만(유저가 <b>왜</b> 폴링을 멈췄나 — 탭을 닫았나·네트워크가 끊겼나)
 * 우리는 그걸 알 필요가 없다. {@code INACTIVE}가 기록하는 건 동기가 아니라
 * <b>"그 시간 동안 폴링이 안 왔다"는 관측 사실</b>이다.
 *
 * <h2>왜 총계로는 부족한가</h2>
 * 셋의 의미가 <b>정반대</b>라 조치가 갈린다 —
 * <ul>
 *   <li>{@link #ADMIT_TTL}·{@link #ADMIT_STALE}: <b>Tenant 귀책</b>(입장권을 쥐고 안 들어옴)</li>
 *   <li>{@link #INACTIVE}: <b>정상 이탈</b>. 조치 없음, 이탈률 지표</li>
 *   <li>{@link #WAITING_TTL}: <b>용량 부족</b>. 슬롯을 늘리라고 통보해야 한다</li>
 * </ul>
 * 합치면 "만료율 99.98%"라는, 아무 조치로도 이어지지 않는 숫자만 남는다(§86 실측).
 *
 * <p>🔴 <b>코드를 재사용하지 마라.</b> {@code TokenStatus}의 3번 결번과 같은 이유다 —
 * 지난 파티션에 이미 쓰인 값의 뜻이 바뀌면 과거 통계의 해석이 통째로 틀어진다.
 */
public enum ExpiredReason {

    /**
     * admitToken TTL(60초) 만료. 입장권을 발급받고 그 안에 쓰지 않았다.
     *
     * <p>🪤 <b>이 사유는 {@code EXPIRED} 이벤트로는 DB에 못 남긴다.</b> 컨슈머의 상태 가드가
     * {@code IF(status = 0, 4, status)}라 {@code ADMIT_ISSUED(1)}에서 통째로 no-op이고,
     * 그 가드는 늦은 입장을 살리려고 일부러 넣은 것이다(§36). 그래서 실제로 DB에 기록되는 것은
     * {@link #ADMIT_STALE}이고, 이 상수는 <b>이벤트에 실려 사유를 잃지 않기 위한</b> 것이다.
     */
    ADMIT_TTL(1),

    /**
     * {@code complete} 유효 창(300초)이 지나도록 {@code ADMIT_ISSUED}에 남았다.
     * {@code ReconcileJob}이 <b>이벤트가 아니라 직접 UPDATE</b>로 정리하는 경로다.
     *
     * <p>{@link #ADMIT_TTL}과 뿌리가 같다(둘 다 입장권을 쥐고 안 들어옴). 다만 <b>기록 주체가
     * 다르고 판정 기준 시각도 다르므로</b>(60초 vs 300초) 구분해 둔다.
     */
    ADMIT_STALE(2),

    /** {@code inactiveTtl} 초과 — 폴링이 끊겼다. 유저 이탈이고 정상이다(§82). */
    INACTIVE(3),

    /** {@code waitingTtl}(기본 7200초) 초과 — 그 시간을 기다리고도 못 뽑혔다. <b>용량 부족 신호</b>다. */
    WAITING_TTL(4);

    private final int code;

    ExpiredReason(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
