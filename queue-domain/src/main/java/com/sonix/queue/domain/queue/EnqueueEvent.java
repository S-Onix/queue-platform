package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * 이유: 토큰 생명주기 이벤트 — 토픽 {@code token-lifecycle} 의 <b>유일한 스키마</b>.
 * 원인: 토픽을 나누면 같은 토큰의 상태 전이 순서가 깨진다(§73 D18).
 * 해결: 한 토픽·한 스키마에 {@code eventType} 을 <b>본문 판별 필드</b>로 둔다(§80).
 *       현재 넷 — ENQUEUED · ADMITTED · COMPLETED · EXPIRED.
 * 🪤 null 검증을 생성자에 넣지 마라 — <b>역직렬화 경로</b>라 인덱스도 모른 채 터져 격리가 막힌다.
 *
 * @author sonix
 * @param eventType {@link TokenEventType} 이름. 아래 정규화 규칙 참조
 * @param admitToken ADMITTED에서 발급된 입장 자격. 그 외 타입은 null일 수 있다
 * @param admittedAt admit 시각(UTC). 🔴 <b>이 값은 {@code tokens.admitted_at}에 적재되지 않는다</b>(§90).
 *                   적재기가 쓰는 것은 <b>null 여부뿐</b>이고, 값은 MySQL의 {@code UTC_TIMESTAMP(3)}가
 *                   찍는다 — 그 컬럼은 verify·complete·reconcile 술어의 <b>좌변</b>인데 우변이 전부
 *                   MySQL 시계라, 앱 시계로 쓰면 한 창을 두 시계로 재게 되기 때문이다.
 *                   그래서 이 필드는 <b>"admit이 일어났다"는 표지</b>로만 쓰인다 —
 *                   {@code EXPIRED}·{@code COMPLETED}가 null을 실어 보내면 컬럼도 NULL로 남고,
 *                   그 NULL 여부가 {@code SUM(admitted_at IS NOT NULL)}(= 입장권 개수의 유일한 근거)을 만든다.
 *                   🪤 <b>"그대로 적재된다"고 되돌리지 마라</b> — 그 한 문장이 §90을 되돌리게 만든다
 */
public record EnqueueEvent(
                String eventType,
                String tokenId,
                String queueId,
                long tenantId,
                String userId,
                long seq,
                Instant issuedAt,
                String admitToken,
                Instant admittedAt,
                /**
                 * 만료 사유 코드({@link ExpiredReason#getCode()}). {@code EXPIRED} 이벤트에서만 채워지고
                 * 나머지 이벤트에선 {@code null}이다 — {@code admitToken}·{@code admittedAt}과 같은 패턴이다.
                 *
                 * <p>🔑 <b>사유는 발행 지점에서 이미 갈려 있다.</b> {@code TokenReclaimJob}의 회수 3경로가
                 * 각각 다른 메서드이고, 어느 규칙이 발화했는지 알면서 부른다. 이 칸이 없던 동안은
                 * 그 정보를 <b>한 줄 뒤에 버리고 있었다</b>.
                 */
                Integer expiredReason
        ) {

    /**
     * 이유: <b>판별 필드가 없는 메시지는 {@code ENQUEUED} 로 읽는다</b> — 곧 하위 호환 규칙이다.
     * 원인: 그런 메시지는 둘뿐이다 — 필드가 생기기 전에 쌓인 것, 롤링 배포 중 구 프로듀서가 보낸 것.
     *       <b>둘 다 enqueue 다</b>(판별 필드 이전엔 enqueue 외 이벤트를 발행하는 코드가 없었다).
     * 해결: 거부를 택하면 <b>토픽 백로그 전체가 DLT 로</b> 간다. 이 규칙엔 잘못 삼킬 위험이 없다 —
     *       값이 <b>있는데</b> 모르는 값이면 정규화되지 않고 소비 측에서 격리된다.
     *
     * @author sonix
     */
   public EnqueueEvent {
        if (eventType == null || eventType.isBlank()) {
            eventType = TokenEventType.ENQUEUED.name();
        }
    }

    /** OK 결과 + 발행 맥락(tenantId, issuedAt)으로 이벤트 생성. admit 관련 두 칸은 아직 없다. */
    public static EnqueueEvent of(long tenantId, String queueId, EnqueueResult result) {
        return new EnqueueEvent(
                TokenEventType.ENQUEUED.name(),
                result.getTokenId(), queueId, tenantId,
                result.getIdentifier(), result.getSeq(), result.getIssuedAt(),
                null, null, null
        );
    }
}
