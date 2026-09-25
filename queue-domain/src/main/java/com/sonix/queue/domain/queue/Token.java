package com.sonix.queue.domain.queue;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 이유: enqueue·admit 에서 발급되는 토큰. 이 프로젝트의 원장 단위다(토큰 1장 = 청구 1건).
 * 🔑 <b>이 객체의 시각은 UTC 다</b>(§77) — JVM 기본 TZ 가 UTC 라 이 클래스만 특별하지 않다.
 * 🔴 상태 전이에서 {@code LocalDateTime.now()} 를 부르지 마라 — <b>호출자가 주입</b>한다(테스트 고정).
 * 🔑 {@code completed_at} 은 §91 이후 채워진다. cancel 은 §82 에서 폐기돼 없다.
 *
 * @author sonix
 */
@Getter
public class Token {

    /**
     * 이유: complete 가 유효한 창(초). <b>두 곳이 이 값을 쓴다</b>.
     * 해결: {@code QueueEngineService.complete} 의 술어와, reconciliation 의 만료 확정 기준이다 —
     *       reconcile 은 이 창이 <b>지난 뒤에야</b> 남은 {@code ADMIT_ISSUED} 를 정리한다.
     * 🔴 <b>숫자를 각자 박지 마라</b> — 갈라지는 순간 정상 complete 가 404 를 받고
     *    원인은 다른 파일에 있게 된다. 실측: admit 후 98초에도 complete 가 200 을 돌려준다.
     *
     * @author sonix
     */
    public static final int COMPLETE_VALID_WINDOW_SECONDS = 300;

    Long id;
    String tokenId;
    String queueId;
    Long tenantId;
    String userId;
    long seq;
    TokenStatus status;
    Integer expiredReason;  // Expired일 경우에
    String admitToken;      // admit 단계에서 생성됨
    LocalDateTime issuedAt;
    LocalDateTime admittedAt;   // admit 시각. verify·complete 유효 창의 기준 (issuedAt이 아니다, §80)

    private Token() {

    }

    /**
     * enqueue-events 이벤트로부터 WAITING 토큰 발행 (Consumer가 사용).
     * issuedAt은 이벤트의 Instant를 UTC 기준 LocalDateTime으로 변환한 값을 넘겨받는다
     * (재시도 시에도 동일해야 UNIQUE(token_id, issued_at) 멱등 성립).
     */
    public static Token issue(String tokenId, String queueId, Long tenantId,
                              String userId, long seq, LocalDateTime issuedAt) {
        return transition(TokenStatus.WAITING, tokenId, queueId, tenantId, userId, seq,
                issuedAt, null, null);
    }

    /**
     * 상태 전이 이벤트({@code token-lifecycle}) → 적재용 토큰 (Consumer가 사용, §80).
     *
     * <p><b>여기서 전이가 확정되는 것이 아니다.</b> 이 객체는 "이 이벤트가 도달시키려는 상태"를
     * 담을 뿐이고, <b>허용 출발 상태의 강제는 DB UPSERT의 가드</b>가 한다. 그래야 이벤트 순서가
     * 뒤집혀 도착해도(입장권은 Redis 커밋 즉시 보이고 발행은 그 뒤라 WAS 1대여도 뒤집힌다, §91) 최종 상태가 같다.
     * 도메인에서 미리 검사하면 <b>지금 DB 상태를 모르는 채</b> 판정하게 되어 근거가 없다.
     *
     * @param admittedAt ADMITTED에서만 값이 있다. 그 외 타입은 null
     */
    public static Token transition(TokenStatus status, String tokenId, String queueId, Long tenantId,
                                   String userId, long seq, LocalDateTime issuedAt,
                                   String admitToken, LocalDateTime admittedAt) {
        return transition(status, tokenId, queueId, tenantId, userId, seq, issuedAt,
                admitToken, admittedAt, null);
    }

    /**
     * 만료 사유({@link ExpiredReason#getCode()})까지 실어 나르는 전이.
     *
     * <p>{@code EXPIRED}에서만 값이 있다. 사유는 <b>회수 배치의 호출 지점에서 이미 갈려 있고</b>
     * (경로별로 다른 메서드다), 이 칸이 없던 동안은 그걸 한 줄 뒤에 버리고 있었다 —
     * 그래서 만료 16만 건이 "왜 만료됐는지" 구분 불가였다(§86).
     */
    public static Token transition(TokenStatus status, String tokenId, String queueId, Long tenantId,
                                   String userId, long seq, LocalDateTime issuedAt,
                                   String admitToken, LocalDateTime admittedAt,
                                   Integer expiredReason) {
        Token token = new Token();
        token.tokenId = tokenId;
        token.queueId = queueId;
        token.tenantId = tenantId;
        token.userId = userId;
        token.seq = seq;
        token.status = status;
        token.expiredReason = expiredReason;
        token.admitToken = admitToken;
        token.issuedAt = issuedAt;
        token.admittedAt = admittedAt;
        return token;
    }

    public static Token reconstruct(Long id, String tokenId, String queueId, Long tenantId,
                                    String userId, long seq, TokenStatus status,
                                    Integer expiredReason, String admitToken,
                                    LocalDateTime issuedAt, LocalDateTime admittedAt) {
        Token token = new Token();
        token.id = id;
        token.tokenId = tokenId;
        token.queueId = queueId;
        token.tenantId = tenantId;
        token.userId = userId;
        token.seq = seq;
        token.status = status;
        token.expiredReason = expiredReason;
        token.admitToken = admitToken;
        token.issuedAt = issuedAt;
        token.admittedAt = admittedAt;
        return token;
    }

}
