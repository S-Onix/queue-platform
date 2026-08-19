package com.sonix.queue.api.queue.dto;


import com.sonix.queue.domain.queue.EnqueueResult;

/**
 * 대기열 진입 응답 DTO.
 *
 * <p>Platform이 Tenant 서버에게 반환하는 성공 응답 (OK 또는 EXISTS).
 * FULL 상태는 이 DTO로 변환하지 않고, Service Layer에서 BusinessException으로 처리한다.
 *
 * <p><b>rank</b>는 Redis 내부 0-based를 사용자 관점 1-based로 변환한 값이다.
 * <b>already</b>는 이미 대기 중인 경우(EXISTS) true가 된다.
 *
 * <p><b>예시 응답:</b>
 * <pre>{@code
 * {
 *   "queueId": "q_xyz789",
 *   "identifier": "user_12345",
 *   "tokenId": "tok_12345",
 *   "rank": 501,
 *   "total": 100,
 *   "already": false
 * }
 * }</pre>
 */
public class EnqueueResponse {

    private final String queueId;
    private final String identifier;
    private final String tokenId;
    private final long seq;
    private final long rank;
    private final long total;
    private final boolean already;

    private EnqueueResponse(String queueId, String identifier, String tokenId, long seq, long rank, long total, boolean already) {
        this.queueId = queueId;
        this.identifier = identifier;
        this.tokenId = tokenId;
        this.seq = seq;
        this.rank = rank;
        this.total = total;
        this.already = already;
    }

    /**
     * EnqueueResult (도메인)로부터 응답 DTO 생성.
     *
     * <p>FULL 상태는 응답 대상이 아니므로 IllegalStateException을 던진다.
     * FULL 처리는 Service Layer에서 QueueFullException으로 처리해야 한다.
     *
     * <p>rank는 Redis 내부 0-based에서 사용자 관점 1-based로 변환된다.
     *
     * @param queueId 대기열 ID
     * @param result 도메인 결과 (OK 또는 EXISTS만 허용)
     * @return 응답 DTO
     * @throws IllegalStateException FULL 상태로 호출된 경우
     */
    public static EnqueueResponse from(String queueId, EnqueueResult result) {
        if (result.isFull()) {
            throw new IllegalStateException(
                    "Cannot convert FULL result to EnqueueResponse. " +
                            "FULL should be handled with QueueFullException in Service layer."
            );
        }

        // Redis 0-based → 사용자 1-based 변환.
        // ⚠️ getRank() + 1을 직접 쓰지 않는다. admit된 사람이 재-enqueue하면 EXISTS이면서
        //    waiting에 없어 rank가 -1로 오는데, +1을 그대로 하면 와이어에 0(=존재하지 않는 순번)이
        //    나간다. 도메인이 이미 "-1은 -1로 둔다"는 계약을 갖고 있다.
        long rankOneBased = result.getDisplayRank();

        // 도메인 편의 메서드 사용 (Status enum 직접 참조 X)
        boolean already = result.isExists();

        return new EnqueueResponse(
                queueId,
                result.getIdentifier(),
                result.getTokenId(),
                result.getSeq(),
                rankOneBased,
                result.getTotal(),
                already
        );
    }

    public String getQueueId() {
        return queueId;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getTokenId() { return tokenId; }

    public long getRank() {
        return rank;
    }

    public long getTotal() {
        return total;
    }

    public boolean isAlready() {
        return already;
    }

    public long getSeq() { return seq; }
}
