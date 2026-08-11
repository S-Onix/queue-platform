package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class QueueEngineService {

    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;
    private final QueueSnapshotCache snapshotCache;   // ②
    private final Clock clock;                        // 시간 주입(테스트 제어)

    public QueueEngineService(QueueRepository queueRepository, QueueEngine queueEngine,
                              EnqueueEventPublisher eventPublisher,
                              QueueSnapshotCache snapshotCache, Clock clock) {
        this.queueRepository = queueRepository;
        this.queueEngine = queueEngine;
        this.eventPublisher = eventPublisher;
        this.snapshotCache = snapshotCache;
        this.clock = clock;
    }

    /**
     * 대기열 진입 처리.
     *
     * @param queueId 대기열 외부 식별자 (예: "q_xyz789")
     * @param identifier 사용자 식별자 (Tenant가 자유 지정)
     * @return 진입 결과 (OK 또는 EXISTS)
     * @throws BusinessException queue가 없거나(QUEUE_NOT_FOUND),
     *                           Enqueue 불가 상태(QUEUE_NOT_ENQUEUEABLE)이거나,
     *                           대기열이 가득 찬(QUEUE_FULL) 경우
     */
    public EnqueueResult enqueue(long tenantId, String queueId, String identifier) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        if (!queue.isEnqueueable()) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ACTIVE);
        }

        EnqueueResult result = queueEngine.enqueue(queueId, identifier);

        if (result.isFull()) {
            throw new BusinessException(ErrorCode.QUEUE_FULL);
        }

        if (result.isOk()) {
            eventPublisher.publish(EnqueueEvent.of(tenantId, queueId, result));
        }

        return result;
    }

    /**
     * queue 조회 + 소유권 검증 (관리용 QueueService와 동일 패턴).
     */
    private Queue findQueueAndVerifyOwner(Long tenantId, String queueId) {
        Queue queue = queueRepository.findByQueueId(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
        if (!queue.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_OWNED);
        }
        return queue;
    }

    public PollResult poll(String queueId, String tokenId, long seq, boolean keepalive){
        boolean ready = false;
        String admitToken = null;

        // 존재(seq)만이 아니라 소유권(tokenId)까지 검증한다. seq는 큐별 INCR이라 추측이 자명해서,
        // 존재 판정만 하면 남의 대기 항목에 ka=1로 keepalive를 걸 수 있다.
        // keepalive 갱신도 이 호출 안에서 원자적으로 처리된다(poll_verify.lua).
        if(!queueEngine.verifyWaiting(queueId, seq, tokenId, keepalive, clock.millis())) {
            throw new BusinessException(ErrorCode.TOKEN_NOT_FOUND);
        }

        QueueSnapshot snap = snapshotCache.get(queueId);

        long rank = (snap.frontSeq() < 0) ? 0 : Math.max(0, seq-snap.frontSeq());
        int next = nextPollAfterSec(rank);

        return new PollResult(ready, admitToken, snap.frontSeq(), snap.total(), next);
    }

    private int nextPollAfterSec(long rank) {
        if(rank <= 50) return 2;
        if(rank <= 1000) return 5;
        if(rank <= 5000) return 10;
        if(rank <= 10000) return 15;
        return 20;
    }

}
