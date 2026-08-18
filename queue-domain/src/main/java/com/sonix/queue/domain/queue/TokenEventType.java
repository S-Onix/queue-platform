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
    ENQUEUED,

    /** admit 발급 — 대기열에서 빠지고 admitToken을 쥐었다. */
    ADMITTED,

    /** admitToken TTL 만료 → WAITING 복귀. */
    RETURNED,

    /** Tenant가 입장 완료를 통보. */
    COMPLETED,

    /** 사용자가 대기를 취소. */
    CANCELLED,

    /** 대기 TTL 초과로 폐기. */
    EXPIRED;

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
