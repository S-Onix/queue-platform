package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueService {
    private final QueueRepository queueRepository;

    public QueueService(QueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Transactional
    QueueResponse createQueue(Long tenantId, QueueCreateRequest request) {
        boolean isExist = queueRepository.existsByTenantIdAndName(tenantId, request.getName());
        if(isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME);
        }

        Queue queue = Queue.create(tenantId, request.getName(), request.getMaxCapacity(), request.getWaitingTtl(), request.getInactiveTtl());
        queueRepository.save(queue);
        return QueueResponse.from(queue);
    }

    /**
     * 🔴 <b>{@code readOnly = true}를 붙이지 않는다.</b> 붙이면 {@code ReplicationRoutingDataSource}가
     * replica로 보내는데(이 프로젝트에서 <b>명시적으로 replica로 가는 유일한 지점</b>이었다),
     * 하필 이 경로가 <b>read-after-write</b>다 — 테넌트가 큐를 만들고 바로 조회하는 흐름이라
     * 복제 지연(idle 실측 15~25ms) 안에 두 번째 요청이 도착하면 <b>404 Q001</b>이 나간다.
     *
     * <p>실측(2026-09-01, 앱 3대 실기동 · REST 전 경로): create → GET 반복에서 <b>8회 중 2회</b>,
     * 다른 하니스로 <b>12회 중 1회</b> 404. 라우팅 로그가 같은 시각에 {@code [master]} → {@code [replica]}로
     * 찍혔다. <b>부하가 없어도 발생하며, 부하로 복제 지연이 커지면 확률이 올라간다.</b>
     *
     * <p>피해자는 <b>테넌트 자동화/IaC</b>다 — 생성 직후 확인이 정상 패턴이라서다. 사람이 콘솔에서
     * 클릭하면 간격이 벌어져 안 걸리고, 그래서 지금까지 드러나지 않았다.
     *
     * <p>🪤 <b>통합 테스트로는 구조적으로 못 잡는다.</b> 테스트 설정이 replica url을 master(3306)로
     * 준다 — 라우팅이 갈라지지 않으므로 어떤 단정도 빨개지지 않는다.
     *
     * <p><b>잃는 것</b>: 읽기 분산. 다만 실측 분산율이 0.9%이고, 그 대부분이 배치의
     * {@code findAll()}일 것으로 <b>추정</b>한다(0.9%의 내역 분해는 <b>미측정</b>이다 —
     * 별도 창에서 잰 배치의 replica/master 비가 근거이지 그 수치의 분해가 아니다).
     * 어느 쪽이든 이 한 줄로 사라지는 몫은 크지 않다.
     * 이 서비스의 DB 읽기는 구조적으로 <b>거의 전부 쓰기 직후</b>다(Redis가 먼저 쓰이고 DB는
     * Kafka를 거쳐 늦게 들어온다) — replica로 옮길 수 있는 읽기가 원래 없다.
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
     * <p><b>왜 전역 핸들러가 아니라 여기인가</b>: {@code IllegalStateException}은 코드 전체에
     * 17곳 있고 대부분은 <b>진짜 500</b>이다({@code TimeZoneGuard}·{@code JwtKeyStore}·
     * {@code RedisQueueEngine}). 전역에서 통째로 409에 매핑하면 프로그래머 오류가
     * "클라이언트 잘못"으로 위장된다. <b>의도를 아는 곳에서만</b> 좁게 바꾼다.
     *
     * <p><b>왜 도메인이 직접 {@code BusinessException}을 던지지 않나</b>: {@code queue-domain}은
     * {@code ErrorCode}를 한 번도 참조하지 않는다. HTTP 상태를 아는 상수를 도메인에 들이면
     * 그 순수성이 깨진다.
     */
    private void guardTransition(Runnable transition) {
        try {
            transition.run();
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.QUEUE_INVALID_STATUS);
        }
    }

    @Transactional
    public QueueResponse deleteQueue(Long tenantId, String queueId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        guardTransition(() -> queue.delete());
        queueRepository.save(queue);

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
