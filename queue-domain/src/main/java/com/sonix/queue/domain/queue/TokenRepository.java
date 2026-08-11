package com.sonix.queue.domain.queue;

import java.util.List;

public interface TokenRepository {
    /**
     * WAITING 토큰들을 벌크로 멱등 적재한다.
     *
     * <p><b>멱등 계약</b>: 이미 존재하는 토큰(동일 {@code (tokenId, issuedAt)})은
     * 조용히 무시되고 오류를 던지지 않는다. outbox at-least-once로 같은 이벤트가
     * 재처리되거나 발행 재시도로 중복돼도 중복 row가 생기지 않는다.
     *
     * <p>구현은 UNIQUE(token_id, issued_at) 위에서 upsert(no-op)로 이 계약을 만족한다.
     * 따라서 호출자는 중복 여부를 사전 검사하지 않아도 되고, 재소비 시 그대로 다시 넘기면 된다.
     *
     * @param tokens 적재할 토큰들 (Consumer가 poll한 배치 단위, 보통 수백 건)
     */
    void saveAllIfAbsent(List<Token> tokens);
}
