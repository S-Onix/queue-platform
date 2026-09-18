package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueService {

    /**
     * 이유: 테넌트당 큐 개수 상한(2026-09-03 실측 확정).
     * 🔑 <b>막는 것은 성능이 아니라 Redis 마스터 용량이다</b> — 개수 자체는 안 느려진다.
     * 문제: {@code maxCapacity} 30만은 <b>큐 하나만</b> 막고 마스터 합계는 아무도 안 막는다 —
     *       전체 60개면 50% 임계를 넘고 120개면 OOM 인데 거절 경로가 없다(§87).
     * 🔴 이 상한이 지키는 것은 자기 큐가 아니라 <b>남의 큐</b>다. 값 20은 최대가 아니라 <b>최소</b>다.
     */
    static final int MAX_QUEUES_PER_TENANT = 20;

    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;

    public QueueService(QueueRepository queueRepository, QueueEngine queueEngine) {
        this.queueRepository = queueRepository;
        this.queueEngine = queueEngine;
    }

    /**
     * 이유: 큐 생성. 🔴 <b>{@code @Transactional} 을 붙이지 않는다 — 붙여도 안 걸린다</b>.
     * 원인: 이 메서드가 package-private 이라 {@code publicMethodsOnly=true} 기본값이 애노테이션을
     *       무시한다(실측: {@code getTransactionAttribute} 가 null). 즉 달려 있어도 무동작이었다.
     * 해결: 제거한다. 살리면 {@code RedisClusterAssigner} 왕복이 <b>DB 커넥션을 잡은 채</b> 돈다.
     * 🪤 <b>남겨두면 더 위험하다</b> — 다음 사람이 "트랜잭션이 있다"고 읽고 가정을 쌓는다(§87 전례).
     *
     * @author sonix
     */
    QueueResponse createQueue(Long tenantId, QueueCreateRequest request) {
        boolean isExist = queueRepository.existsByTenantIdAndName(tenantId, request.getName());
        if(isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME);
        }

        // 이유: 동시 생성의 초과분 상계는 **동시 요청 수**다. exists·count·save 가 각각 다른
        //       autocommit 커넥션에서 돌아 서로를 못 본다 — 50개를 동시에 쏘면 전부 COUNT=0 을 읽는다.
        // 해결: **그래도 잠그지 않는다** — 임계 60 에 상한이 20 이라 여유가 3배고, 분산 락은
        //       콜드패스에 과하다. **막는 대상이 악의가 아니라 오타라 상계가 유계면 족하다.**
        if (queueRepository.countActiveByTenantId(tenantId) >= MAX_QUEUES_PER_TENANT) {
            throw new BusinessException(ErrorCode.QUEUE_LIMIT_EXCEEDED);
        }

        Queue queue = Queue.create(tenantId, request.getName(), request.getMaxCapacity(), request.getWaitingTtl(), request.getInactiveTtl());
        queueRepository.save(queue);
        return QueueResponse.from(queue);
    }

    /**
     * 이유: 큐 조회. 🔴 <b>{@code readOnly = true} 를 붙이지 않는다.</b>
     * 문제: 붙이면 replica 로 가는데 하필 이 경로가 <b>read-after-write</b> 다 —
     *       테넌트가 큐를 만들고 바로 확인하면 복제 지연 안에서 <b>404 Q001</b> 이 나간다.
     * 해결: master 로 읽는다. 실측(2026-09-01, 앱 3대): 수정 전 8회 중 2회·12회 중 1회 404 → 0/30.
     * 🪤 <b>통합 테스트로는 구조적으로 못 잡는다</b> — 테스트가 replica url 을 master 로 준다(§4-3).
     *
     * @author sonix
     */
    public QueueResponse getQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse updateQueue(Long tenantId, String queueId, QueueUpdateRequest request) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        guardTransition(() -> queue.update(request.getName()));
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse pauseQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        guardTransition(() -> queue.pause());
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    @Transactional
    public QueueResponse resumeQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        guardTransition(() -> queue.resume());
        queueRepository.save(queue);

        return QueueResponse.from(queue);
    }

    /**
     * 도메인의 상태 가드({@code IllegalStateException})를 409 {@code QE006}으로 바꾼다.
     *
     * <p>🔴 <b>전역 핸들러로 올리지 마라.</b> {@code IllegalStateException}은 코드 전체에 17곳
     * 있고 대부분 <b>진짜 500</b>이다 — 통째로 409에 매핑하면 프로그래머 오류가 "클라이언트
     * 잘못"으로 위장된다. 도메인이 직접 {@code BusinessException}을 던지지 않는 것은
     * {@code queue-domain}에 HTTP를 아는 {@code ErrorCode}를 들이지 않기 위해서다.
     */
    private void guardTransition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.QUEUE_INVALID_STATUS);
        }
    }

    /**
     * 이유: 큐 삭제. <b>되돌릴 수 없다</b>(DELETED 에서 나가는 전이가 없다).
     * 🔑 <b>DB 는 소프트 삭제, Redis 는 실제로 지운다</b> — 발급된 토큰은 청구 대상이라 원장을
     *    지우면 과금 근거가 사라지고, Redis 를 안 지우면 그 메모리가 영구 점유된다.
     * 🔴 <b>정리가 실패하면 삭제도 실패한다</b> — 같은 트랜잭션이라 {@code save} 가 함께 롤백된다(§4-2).
     * 🪤 그래도 정리를 저장 <b>뒤</b>에 두는 순서는 유지한다 — 앞에 두면 살아 있는 큐의 대기자가 날아간다.
     *
     * @author sonix
     */
    @Transactional
    public QueueResponse deleteQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        guardTransition(() -> queue.delete());
        queueRepository.save(queue);

        queueEngine.purgeDeleted(queueId);

        return QueueResponse.from(queue);
    }


    private Queue findQueueAndVerifyOwner(Long tenantId, String queueId) {
        Queue queue = queueRepository.findByQueueId(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
        if (!queue.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_OWNED);
        }
        return queue;
    }
}
