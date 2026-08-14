package com.sonix.queue.domain.queue;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Queue
 *  - enqueue 요청시 발급되는 토큰 정보
 *  - admit 요청시 발급되는 토큰 정보
 *
 * <p><b>⚠️ 이 객체의 시각은 UTC다.</b> 이 프로젝트는 2026-08-12부로 <b>전 테이블을 UTC로 통일</b>했다
 * (DECISIONS §77). JVM 기본 TZ가 UTC로 고정돼 있어 {@code LocalDateTime.now()}도 UTC 벽시계를 낸다 —
 * 즉 이 클래스만 특별하지 않고, 다른 도메인 모델과 규약이 같다.
 *
 * <p>그래도 이 클래스에서 지킬 것 둘:
 * <ol>
 *   <li><b>상태 전이 메서드에서 {@code LocalDateTime.now()}를 호출하지 마라.</b>
 *       시각은 <b>호출자에게서 주입받는다</b> — {@code issue()}가 이미 그렇게 돼 있다.
 *       도메인이 시계를 직접 읽으면 테스트에서 고정할 수 없다. TZ와 무관한 이유다.</li>
 *   <li>Kafka 이벤트의 {@code Instant}를 변환할 때는 {@code ZoneOffset.UTC}를 쓴다
 *       ({@code TokenLifecycleConsumer.toToken()} 참조). 고정 오프셋이라 재처리해도 값이 같고,
 *       그 값이 {@code UNIQUE(token_id, issued_at)}의 절반이라 멱등성이 걸려 있다.</li>
 * </ol>
 *
 * <p><b>Sprint 7에서 추가될 {@code completedAt}/{@code cancelledAt}/{@code expiredAt}도 UTC다.</b>
 * DB 컬럼은 이미 있으나 전부 NULL이다. {@code schema.sql}의 [파티션 운영 쿼리]가
 * {@code AVG(TIMESTAMPDIFF(SECOND, issued_at, completed_at))}을 이미 쓰고 있으므로,
 * 규약을 어기면 그 쿼리가 곧바로 틀린 숫자를 낸다.
 *
 * <p>배경과 대안 비교: {@code doc/DECISIONS.md}, DDL 주석: {@code doc/schema.sql}
 * */
@Getter
public class Token {

    Long id;
    String tokenId;
    String queueId;
    Long tenantId;
    String userId;
    long seq;
    TokenStatus status;
    Integer expiredReason;  // Expired일 경우에
    String admitToken;      // admit 단계에서 생성됨
    boolean redisSyncNeeded;// Redis와 데이터 싱크가 맞는지 확인 (Redis가 강제로 다운될 경우를 대비)
    LocalDateTime issuedAt;

    private Token() {

    }

    /**
     * enqueue-events 이벤트로부터 WAITING 토큰 발행 (Consumer가 사용).
     * issuedAt은 이벤트의 Instant를 UTC 기준 LocalDateTime으로 변환한 값을 넘겨받는다
     * (재시도 시에도 동일해야 UNIQUE(token_id, issued_at) 멱등 성립).
     */
    public static Token issue(String tokenId, String queueId, Long tenantId,
                              String userId, long seq, LocalDateTime issuedAt) {
        Token token = new Token();
        token.tokenId = tokenId;
        token.queueId = queueId;
        token.tenantId = tenantId;
        token.userId = userId;
        token.seq = seq;
        token.status = TokenStatus.WAITING;
        token.expiredReason = null;
        token.admitToken = null;
        token.redisSyncNeeded = false;
        token.issuedAt = issuedAt;
        return token;
    }

    public static Token reconstruct(Long id, String tokenId, String queueId, Long tenantId,
                                    String userId, long seq, TokenStatus status,
                                    Integer expiredReason, String admitToken,
                                    boolean redisSyncNeeded, LocalDateTime issuedAt) {
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
        token.redisSyncNeeded = redisSyncNeeded;
        token.issuedAt = issuedAt;
        return token;
    }

}
