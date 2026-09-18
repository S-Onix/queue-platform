package com.sonix.queue.domain.queue;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이유: {@code token-lifecycle} 토픽에 실리는 이벤트 종류. <b>토픽은 하나다</b>(§73 D16·D18).
 * 문제: Kafka 헤더로 구분하면 헤더가 유실됐을 때 admit 이벤트가 <b>예외 없이</b> enqueue 로 읽힌다.
 * 해결: <b>본문의 판별 필드</b>로 가른다 — 값이 남아 {@link #from} 이 "모르는 타입"으로 잡는다.
 * 🔴 <b>이름 문자열이 계약이다</b> — 상수 이름을 바꾸면 흘러가는 구 메시지가 못 읽힌다.
 * 🔴 <b>선언 순서도 고정</b>(상태 전이 순) — {@code persistGrouped} 가 EnumMap 순회에 기댄다.
 *
 * @author sonix
 */
public enum TokenEventType {

    /** 대기열 진입. {@code tokens} 행 생성(멱등). */
    ENQUEUED(TokenStatus.WAITING),

    /** admit 발급 — 대기열에서 빠지고 admitToken을 쥐었다. */
    ADMITTED(TokenStatus.ADMIT_ISSUED),

    /** Tenant가 입장 완료를 통보. */
    COMPLETED(TokenStatus.COMPLETED),

    // 🔴 CANCELLED 자리가 여기였다. §82가 Cancel API를 폐기해 상수를 지웠고 status 3은 결번이다.
    //    (구 코드엔 "사용자가 대기를 취소"라는 javadoc만 상수 없이 남아 있어, 바로 아래
    //     EXPIRED가 취소인 것처럼 읽혔다.)

    /** 대기 TTL 초과·이탈로 폐기. 회수 배치 3경로가 발행한다. */
    EXPIRED(TokenStatus.EXPIRED);

    private final TokenStatus targetStatus;

    TokenEventType(TokenStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    /**
     * 이유: 이 이벤트가 도달시키려는 상태(§80 가드 표의 "도착" 칸).
     * 🔑 <b>도달을 보장하지 않는다</b> — 허용 출발을 강제하는 것은 UPSERT 의 {@code IF(status = ...)}
     *    가드이고 여기는 목표값일 뿐이다. Kafka 가 At-Least-Once 라 재전달이 일상이기 때문이다.
     * 🔴 {@code 4}(EXPIRED)에 닿는 경로는 <b>셋</b> — ①waitingTtl·inactiveTtl ②ReconcileJob 직접 UPDATE
     *    ③랙 구간의 admitToken TTL 만료(DB 가 아직 0). ①②는 설계고 <b>③은 결함이다</b>(2026-09-18).
     *
     * @author sonix
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
