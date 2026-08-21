package com.sonix.queue.domain.queue;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code token-lifecycle} 토픽에 실리는 이벤트의 종류.
 *
 * <p><b>토픽은 하나다.</b> 같은 토큰의 상태 전이는 같은 토픽·같은 파티션 안에서만 순서가
 * 보장되므로 타입별로 토픽을 나눌 수 없다(DECISIONS §73 D16·D18). 그래서 구분은
 * <b>메시지 본문의 판별 필드</b>({@link EnqueueEvent#eventType()})가 진다 (§80).
 *
 * <p><b>Kafka 헤더로 구분하지 않는 이유:</b> 헤더는 본문과 따로 다녀서, 헤더를 못 읽는 구
 * 컨슈머에게 admit 이벤트가 도착하면 <b>예외 없이</b> enqueue로 해석돼 조용히 적재된다.
 * 판별 필드는 본문에 있으므로 같은 상황에서 값이 남고, 아래 {@link #from(String)}이
 * "모르는 타입"으로 잡아낸다.
 *
 * <p>이름 문자열이 곧 계약이다 — <b>상수 이름을 바꾸면 흘러가는 메시지가 깨진다.</b>
 * 순서·개수는 바꿔도 되지만 이름은 못 바꾼다(ordinal이 아니라 name으로 직렬화된다).
 *
 * <p>현재 발행되는 것은 {@link #ENQUEUED} 뿐이다. 나머지는 Sprint 7(admit)·9(복귀/만료)에서
 * 발행되며, 소비 측 처리도 그때 붙는다.
 */
public enum TokenEventType {

    /** 대기열 진입. {@code tokens} 행 생성(멱등). */
    ENQUEUED(TokenStatus.WAITING),

    /** admit 발급 — 대기열에서 빠지고 admitToken을 쥐었다. */
    ADMITTED(TokenStatus.ADMIT_ISSUED),

    /** Tenant가 입장 완료를 통보. */
    COMPLETED(TokenStatus.COMPLETED),

    /** 사용자가 대기를 취소. */

    /** 대기 TTL 초과로 폐기. */
    EXPIRED(TokenStatus.EXPIRED);

    private final TokenStatus targetStatus;

    TokenEventType(TokenStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    /**
     * 이 이벤트가 도달시키려는 상태 (§80 가드 표의 "도착" 칸).
     *
     * <p><b>도달을 보장하지 않는다.</b> 허용 출발 상태를 강제하는 것은 소비 측 UPSERT의
     * {@code IF(status = ...)} 가드이고, 여기는 "행이 없을 때 새로 넣을 값"이자 그 가드의
     * 목표값일 뿐이다. 예컨대 이미 {@code COMPLETED}(2)인 행에 {@code ADMITTED}가 재전달돼도
     * 2가 유지된다 — Kafka가 At-Least-Once라 재전달이 일상이기 때문이다.
     *
     * <p>🔴 {@code EXPIRED}는 <b>정상 경로에서 도달하지 않는다.</b> admitToken TTL 만료자는
     * {@code status = 1}인데 소비 측 가드가 {@code IF(status = 0, 4, status)}라 no-op이다.
     * 의도된 동작이다(§36) — {@code complete}의 술어가 {@code status IN (0, 1)}이고 유효 창이
     * 300초라 <b>늦은 입장이 정상 경로로 실재</b>한다. 가드를 넓히면 그 경로가 죽는다.
     * {@code 4}에 실제로 닿는 것은 {@code waitingTtl}·{@code inactiveTtl} 만료(출발이 0)뿐이다.
     */
    public TokenStatus targetStatus() {
        return targetStatus;
    }

    /**
     * 이름 → 상수. {@code values()}는 호출마다 배열을 복제하므로 한 번만 만들어 둔다
     * (배치 한 건마다 도는 경로다).
     */
    private static final Map<String, TokenEventType> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    /**
     * @param name 판별 필드 값
     * @return 해당 타입. <b>모르는 이름이면 {@code null}</b> — 소비 측이 "미지 타입"으로
     *         분기해야 하므로 예외 대신 null로 돌려준다. 예외로 만들면 역직렬화 단계에서
     *         터져 <b>어느 레코드가 문제인지</b>(인덱스)를 잃는다
     */
    public static TokenEventType from(String name) {
        // 판별 필드가 없는 구 메시지의 정규화는 EnqueueEvent의 정식 생성자가 이미 끝냈으므로
        // 여기로 null이 오지 않는다. 불변 Map은 null 키 조회에서 NPE를 던지므로 방어만 둔다.
        if (name == null) return null;
        return BY_NAME.get(name);
    }
}
