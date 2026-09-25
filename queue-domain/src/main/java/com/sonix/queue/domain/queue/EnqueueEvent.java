package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * 이유: 토큰 생명주기 이벤트 — 토픽 {@code token-lifecycle} 의 <b>유일한 스키마</b>. 토픽을 나누면 전이 순서가 깨진다(§73 D18).
 * 해결: {@code eventType} 을 본문 판별 필드로 둔다(§80) — ENQUEUED · ADMITTED · COMPLETED · EXPIRED.
 * 🪤 null 검증을 생성자에 넣지 마라 — 역직렬화 경로라 인덱스도 모른 채 터져 격리가 막힌다.
 *
 * @author sonix
 * @param eventType  {@link TokenEventType} 이름. 아래 정규화 규칙 참조
 * @param admitToken ADMITTED에서 발급된 입장 자격. 그 외 타입은 null일 수 있다
 * @param admittedAt 🔴 <b>값은 적재되지 않는다</b>(§90) — null 여부만 쓰이고 시각은 MySQL 이 찍는다.
 *                   "그대로 적재된다"고 되돌리지 마라. 근거 §96-6
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
