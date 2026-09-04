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

    /**
     * 테넌트당 큐 개수 상한 (2026-09-03 실측 확정).
     *
     * <p><b>막는 것은 성능이 아니라 Redis 마스터 용량이다.</b> 개수 자체는 안 느려진다(드레인
     * 비용은 활성 큐의 함수, DB 목록 조회는 큐 1만 개에서 7.75ms). 깨지는 것은 {@code maxCapacity}
     * 30만이 <b>큐 하나만</b> 막고 마스터 합계는 아무도 안 막는다는 점이다 — 큐 분포가 균등하므로
     * 전체 60개면 한 마스터가 §75 D27-3의 50% 임계를 넘고 120개면 OOM이다(근거·실측 §87).
     *
     * <p>🔴 <b>그때 막아 세울 코드가 없다.</b> {@code RedisClusterAssigner.assign()}에 거절 경로가
     * 없어 꽉 찬 클러스터로 계속 보낸다. 종착점은 {@code noeviction} OOM이고, 재현해 보면
     * <b>같은 마스터의 다른 테넌트</b> enqueue만 죽고 폴링은 살아 "줄은 보이는데 못 들어가는"
     * 상태가 된다. 이 상한이 지키는 것은 자기 큐가 아니라 <b>남의 큐</b>다.
     *
     * <p>값 20은 "견딜 수 있는 최대"가 아니라 "필요를 채우는 최소"다(목표 전제 8개의 2.5배).
     * <b>올리는 것은 하위호환, 내리는 것은 파괴적 변경</b>이라 낮은 쪽에서 시작한다.
     */
    static final int MAX_QUEUES_PER_TENANT = 20;

    private final QueueRepository queueRepository;

    public QueueService(QueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    /**
     * 🔴 <b>{@code @Transactional}을 붙이지 않는다 — 붙여도 안 걸리고, 걸려도 사줄 것이 없다.</b>
     *
     * <p>달려 있었지만 <b>한 번도 동작한 적이 없다</b> — 이 메서드만 package-private인데
     * ({@code QueueController}가 같은 패키지라 의도된 캡슐화) 프록시 트랜잭션은
     * {@code publicMethodsOnly = true} 기본값 때문에 non-public 애노테이션을 무시한다
     * (실측: 여기만 {@code getTransactionAttribute}가 {@code null}). 제거는 동작을 바꾸지 않는다.
     *
     * <p>public으로 바꿔 살리면 셋 다 손해다 — 쓰기가 {@code save} 하나뿐이라 <b>롤백할 대상이 없고</b>,
     * {@code REPEATABLE-READ}의 비잠금 스냅샷 읽기라 <b>COUNT 경쟁도 못 막고</b>, 🔴 신규 큐일 때
     * {@code QueueJpaAdapter.save}가 부르는 {@code RedisClusterAssigner.assign()}의
     * {@code INFO memory} 왕복이 <b>DB 커넥션을 잡은 채</b> 돌게 된다.
     *
     * <p>🪤 <b>남겨두는 쪽이 더 위험하다.</b> 다음 사람이 "트랜잭션이 있다"고 읽고 그 위에 가정을
     * 쌓는다 — 실제로 {@code countByTenantIdAndStatusNot}의 라우팅 근거가 이것 때문에 틀리게
     * 적혔다(§87). 결론은 같았지만 근거가 우연이었다.
     */
    QueueResponse createQueue(Long tenantId, QueueCreateRequest request) {
        boolean isExist = queueRepository.existsByTenantIdAndName(tenantId, request.getName());
        if(isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME);
        }

        // 🪤 동시 생성의 초과분 상계는 **동시 요청 수**다. exists·count·save가 각각 다른
        // autocommit 커넥션에서 돌아(트랜잭션 없음 — 위 javadoc), 서로 다른 이름으로 50개를
        // 동시에 쏘면 50개 전부 COUNT=0을 읽는다. IaC 병렬 생성이 그 형태다.
        // 그래도 잠그지 않는다 — 임계 60에 상한이 20이라 여유가 3배고, "테넌트당 N행"은 DB
        // 제약으로 표현할 수 없으며 분산 락은 콜드패스에 과하다.
        // **막는 대상이 악의가 아니라 오타이므로 상계가 유계면 족하다.**
        if (queueRepository.countActiveByTenantId(tenantId) >= MAX_QUEUES_PER_TENANT) {
            throw new BusinessException(ErrorCode.QUEUE_LIMIT_EXCEEDED);
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
     * <p>실측(2026-09-01, 앱 3대 · REST 전 경로): create → GET 반복에서 8회 중 2회, 다른 하니스로
     * 12회 중 1회 404. <b>부하가 없어도 발생하고 복제 지연이 커지면 확률이 오른다.</b>
     * 피해자는 생성 직후 확인이 정상 패턴인 <b>테넌트 자동화/IaC</b>다.
     *
     * <p>🪤 <b>통합 테스트로는 구조적으로 못 잡는다</b> — 테스트 설정이 replica url을 master(3306)로
     * 줘서 라우팅이 갈라지지 않는다.
     *
     * <p><b>잃는 것은 거의 없다.</b> 이 서비스의 DB 읽기는 구조적으로 대부분 쓰기 직후라
     * (Redis가 먼저 쓰이고 DB는 Kafka를 거쳐 늦게 들어온다) replica로 옮길 읽기가 원래 없다.
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
