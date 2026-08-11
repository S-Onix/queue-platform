package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * Enqueue 완료 이벤트
 *
 * <p>Consumer가 이 정보만으로 tokens row를 INSERT할 수 있도록 필요한 값을 모두 담는다:
 * tokenId, queueId, tenantId, userId(=identifier), seq(불변 score), issuedAt.
 * 순수 record라 도메인에 둬도 헥사고날 위반이 아니다
 */
public record EnqueueEvent(
                String tokenId,
                String queueId,
                long tenantId,
                String userId,
                long seq,
                Instant issuedAt
        ) {
    /** OK 결과 + 발행 맥락(tenantId, issuedAt)으로 이벤트 생성. */
    public static EnqueueEvent of(long tenantId, String queueId, EnqueueResult result) {
        return new EnqueueEvent(
                result.getTokenId(), queueId, tenantId,
                result.getIdentifier(), result.getSeq(), result.getIssuedAt()
        );
    }
}
