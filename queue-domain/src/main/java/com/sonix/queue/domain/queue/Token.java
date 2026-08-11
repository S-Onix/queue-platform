package com.sonix.queue.domain.queue;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Queue
 *  - enqueue 요청시 발급되는 토큰 정보
 *  - admit 요청시 발급되는 토큰 정보
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
