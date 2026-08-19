package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * 토큰 생명주기 이벤트 (토픽 {@code token-lifecycle}의 유일한 스키마)
 *
 * <p>Consumer가 이 정보만으로 tokens row를 INSERT할 수 있도록 필요한 값을 모두 담는다:
 * eventType, tokenId, queueId, tenantId, userId(=identifier), seq(불변 score), issuedAt.
 * 순수 record라 도메인에 둬도 헥사고날 위반이 아니다
 *
 * <p><b>{@code eventType}이 판별 필드다</b>(§80). 토픽을 나누면 같은 토큰의 상태 전이 순서가
 * 깨지므로(§73 D18) 한 토픽·한 스키마에 싣고 본문 필드로 구분한다. 현재 발행되는 값은
 * {@code ENQUEUED} 하나뿐이고, admit·complete 등은 Sprint 7 이후 같은 스키마로 실린다.
 *
 * <p><b>{@code admitToken}·{@code admittedAt}은 타입에 따라 null이다.</b> 이벤트마다 존재하는
 * 값이 다른데 스키마는 하나여서 생기는 일이며, 소비 측 UPSERT가 타입별로 필요한 칸만 쓰므로
 * 나머지가 null인 것이 정상이다(§80 가드 표).
 *
 * <table><caption>타입별 null 규약</caption>
 *   <tr><th>eventType</th><th>admitToken</th><th>admittedAt</th></tr>
 *   <tr><td>{@code ENQUEUED}</td><td>null</td><td>null</td></tr>
 *   <tr><td>{@code ADMITTED}</td><td><b>필수</b></td><td><b>필수</b></td></tr>
 *   <tr><td>{@code COMPLETED}</td><td>필수(자격 증명)</td><td>null</td></tr>
 *   <tr><td>{@code RETURNED}·{@code CANCELLED}·{@code EXPIRED}</td><td>null</td><td>null</td></tr>
 * </table>
 *
 * <p>여기서 강제하지 않는 이유: 이 record는 <b>역직렬화 경로</b>이기도 하다. 정식 생성자에서
 * 던지면 잘못된 메시지 한 건이 <b>배치 안 어느 인덱스인지 모르는 채</b> 터져 그 한 건만
 * 격리하는 길이 막힌다({@link TokenEventType#from}이 예외 대신 null을 주는 것과 같은 이유).
 *
 * @param eventType {@link TokenEventType} 이름. 아래 정규화 규칙 참조
 * @param admitToken ADMITTED에서 발급된 입장 자격. 그 외 타입은 null일 수 있다
 * @param admittedAt admit 시각(UTC). {@code tokens.admitted_at}에 그대로 적재되며
 *                   verify·complete의 유효 창 기준이다 — {@code issuedAt}("줄 선 시각")이 아니다
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
                Instant admittedAt
        ) {

    /**
     * <b>판별 필드가 없는 메시지는 {@code ENQUEUED}로 읽는다.</b>
     *
     * <p>Jackson은 record의 이 정식 생성자로 역직렬화하므로, 이 한 줄이 곧 <b>하위 호환 규칙</b>이다.
     * 판별 필드가 없는 메시지는 두 종류뿐이다 — (1) 이 필드가 생기기 전에 토픽에 이미 쌓인 것,
     * (2) 롤링 배포 중 아직 재기동하지 않은 구 프로듀서가 보내는 것. <b>둘 다 enqueue 이벤트다</b>
     * (판별 필드 이전에는 enqueue 외의 이벤트를 발행하는 코드가 존재하지 않았다).
     * 그래서 {@code ENQUEUED}로 읽는 것이 "구 메시지를 지금까지와 똑같이 처리한다"와 같은 말이다.
     *
     * <p>거부(미지 타입 취급)를 택하면 <b>토픽에 쌓인 백로그 전체가 DLT로</b> 간다.
     * 반대로 이 규칙에는 잘못 삼킬 위험이 없다 — 없는 필드를 채우는 것뿐이고,
     * 값이 <b>있는데</b> 모르는 값이면 그건 정규화되지 않고 소비 측에서 격리된다.
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
                null, null
        );
    }
}
