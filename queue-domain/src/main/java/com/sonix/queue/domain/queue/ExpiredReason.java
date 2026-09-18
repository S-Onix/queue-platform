package com.sonix.queue.domain.queue;

/**
 * 이유: 토큰이 <b>왜</b> 만료됐는가. {@code tokens.expired_reason}(TINYINT)에 저장한다.
 * 문제: 총계만 있으면 "만료율 99.98%"라는, 아무 조치로도 이어지지 않는 숫자만 남는다(§86 실측).
 * 원인: 넷의 의미가 정반대라 조치가 갈린다 — ADMIT_TTL·ADMIT_STALE 은 <b>Tenant 귀책</b>,
 *       INACTIVE 는 <b>정상 이탈</b>(조치 없음), WAITING_TTL 은 <b>용량 부족</b>이다.
 * 🔴 <b>코드를 재사용하지 마라</b> — 지난 파티션에 쓰인 값의 뜻이 바뀌면 과거 통계가 틀어진다.
 *
 * @author sonix
 */
public enum ExpiredReason {

    /**
     * 이유: admitToken TTL(60초) 만료 — 입장권을 받고 그 안에 쓰지 않았다.
     * 🔴 <b>"이 사유는 DB 에 못 남긴다"는 거짓이었다 — 랙 구간에서는 남는다</b>(실측 259건, 2026-09-18).
     * 원인: 컨슈머 가드 {@code IF(status = 0, 4, status)} 의 status 는 <b>DB 값</b>인데 회수 판정은
     *       Redis 가 한다. ADMITTED 가 아직 적재 전이면 0→4 가 적용되고 뒤늦은 ADMITTED 는 no-op 이라
     *       {@code admit_token}·{@code admitted_at} 이 <b>영구 NULL</b> 이 된다. 보통은 {@link #ADMIT_STALE} 로 남는다(259 : 30,071).
     *
     * @author sonix
     */
    ADMIT_TTL(1),

    /**
     * 이유: complete 유효 창(300초)이 지나도록 {@code ADMIT_ISSUED} 에 남았다.
     * 해결: {@code ReconcileJob} 이 <b>이벤트가 아니라 직접 UPDATE</b> 로 정리한다.
     *       {@link #ADMIT_TTL} 과 뿌리는 같지만 <b>판정 주체와 기준 시각이 다르다</b>(60초 vs 300초).
     * 🪤 <b>이 상수를 참조하는 코드는 없다</b>(2026-09-15 전수) — 실제 값은
     *    {@code TokenJpaRepository} 의 {@code expired_reason = 2} <b>리터럴</b>이다. 고칠 땐 두 곳을 같이 고쳐라.
     *
     * @author sonix
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
