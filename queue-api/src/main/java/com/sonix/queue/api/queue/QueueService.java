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
     * <p><b>막는 것은 성능이 아니라 Redis 마스터 용량이다.</b> 드레인 비용은 그 틱에 유입이 있는
     * <b>활성</b> 큐의 함수라({@code BatchProcessor.groupByQueueId}) 유휴 큐는 0이고, DB도 큐
     * 10,000개에서 목록 조회 7.75ms로 안 깨진다(실측). 즉 <b>개수 자체는 느려지지 않는다.</b>
     *
     * <p>깨지는 것은 이쪽이다 — {@code maxCapacity} 상한 30만은 <b>큐 하나만</b> 막고 마스터
     * 합계를 아무도 안 막는다. 실측 477 B/명 기준 마스터({@code maxmemory} 4GB)는 약 900만 명이고
     * §75 D27-3의 50% 임계는 450만 명 = 30만짜리 큐 <b>15개</b>다. 큐→마스터 분포는 균등하므로
     * (해시태그 keyslot 1,000개 실측 25.1/25.0/24.9/25.0%) <b>전체 60개</b>면 한 마스터가 15개를
     * 받아 임계가 무너지고, 120개면 OOM이다. 목표 전제는 마스터당 2개 = <b>전체 8개</b>다.
     *
     * <p>🔴 <b>그때 막아 세울 코드가 없다.</b> {@code RedisClusterAssigner.assign()}은 1 아니면 2를
     * 반환할 뿐 <b>거절 경로가 없고</b>, cluster2가 꽉 차도 계속 cluster2로 보낸다(D29 단조 가드로
     * 되돌아오지도 않는다). 종착점은 {@code noeviction}의 OOM이며, <b>실측으로 재현했다</b>:
     * 같은 마스터에 놓인 다른 테넌트의 enqueue가 {@code OOM command not allowed}로 죽고
     * <b>폴링(읽기)은 살아</b> 대기자는 "줄은 보이는데 못 들어가는" 상태가 된다.
     * 즉 이 상한이 지키는 것은 자기 큐가 아니라 <b>같은 마스터에 sticky로 묶인 남의 큐</b>다.
     *
     * <p>값 20인 이유: 모든 테넌트가 한도를 꽉 채워도 3개 테넌트까지 50% 임계 안에 산다.
     * 목표 전제(8개)의 2.5배 여유라 미래 이벤트를 미리 만들어 두는 운용을 막지 않는다.
     * <b>올리는 것은 하위호환이고 내리는 것은 파괴적 변경</b>이라 "견딜 수 있는 최대"가 아니라
     * "필요를 채우는 최소"에서 시작한다(§80 ⑦의 {@code count} 상한 100과 같은 기준).
     */
    static final int MAX_QUEUES_PER_TENANT = 20;

    private final QueueRepository queueRepository;

    public QueueService(QueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    /**
     * 🔴 <b>{@code @Transactional}을 붙이지 않는다 — 붙여도 안 걸리고, 걸려도 사줄 것이 없다.</b>
     *
     * <p>여기엔 {@code @Transactional}이 달려 있었지만 <b>한 번도 동작한 적이 없다.</b> 이 메서드만
     * package-private인데({@code QueueController}가 같은 패키지라 의도된 캡슐화다), 프록시 기반
     * 트랜잭션은 {@code AnnotationTransactionAttributeSource}의 기본
     * {@code publicMethodsOnly = true} 때문에 <b>non-public 메서드의 애노테이션을 무시한다.</b>
     * 실측으로 확인했다 — 이 메서드만 {@code getTransactionAttribute}가 {@code null}이고
     * {@code updateQueue}·{@code pauseQueue}·{@code deleteQueue} 셋은
     * {@code PROPAGATION_REQUIRED}가 나온다. <b>즉 이 제거는 런타임 동작을 바꾸지 않는다.</b>
     *
     * <p><b>그러면 public으로 바꿔 살리면 되지 않나 — 아니다. 셋 다 손해다.</b>
     * <ul>
     *   <li><b>원자성을 못 산다.</b> 이 메서드의 쓰기는 {@code save} <b>하나뿐</b>이다.
     *       단일 쓰기에 트랜잭션을 감싸도 롤백할 대상이 없다</li>
     *   <li><b>경쟁도 못 막는다.</b> 격리 수준은 {@code REPEATABLE-READ}(실측)이고 평범한
     *       {@code SELECT COUNT}는 <b>비잠금 스냅샷 읽기</b>라 갭 락을 잡지 않는다. 동시 생성이
     *       상한을 넘기는 것은 트랜잭션 유무와 무관하다</li>
     *   <li>🔴 <b>Redis I/O가 DB 트랜잭션 안으로 들어온다.</b> {@code QueueJpaAdapter.save}는
     *       <b>신규 큐일 때만</b> {@code RedisClusterAssigner.assign()}을 부르고(§75), 그건
     *       master 노드 수만큼 {@code INFO memory} 왕복을 낸다. 지금은 그 왕복이 DB 커넥션을
     *       잡지 않는데, 트랜잭션을 살리면 잡게 된다 — 이 레포가 다른 곳에서 일부러 피해온
     *       형태다({@code QueueEngineService.admit}에 트랜잭션을 안 붙인 이유와 같다)</li>
     * </ul>
     *
     * <p>🪤 <b>남겨두는 쪽이 더 위험하다.</b> 달려 있으면 다음 사람이 "트랜잭션이 있다"고 읽고
     * 그 위에 가정을 쌓는다. 실제로 {@code QueueJpaRepository.countByTenantIdAndStatusNot}의
     * 라우팅 근거가 이것 때문에 한 번 틀리게 적혔다(§87) — <b>결론은 master로 같았지만
     * 근거가 우연이었다.</b>
     */
    QueueResponse createQueue(Long tenantId, QueueCreateRequest request) {
        boolean isExist = queueRepository.existsByTenantIdAndName(tenantId, request.getName());
        if(isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME);
        }

        // 🪤 동시 생성의 초과분 상계는 "한두 개"가 아니라 **동시 요청 수**다. COUNT와 INSERT가
        // 원자적이 아닐 뿐 아니라 **이 메서드에는 트랜잭션이 없다**(위 javadoc 참조 — 붙여도
        // 안 걸리고 걸려도 사줄 것이 없어서 뗐다). exists·count·save가 각각 다른 autocommit
        // 커넥션에서 돈다.
        // 큐 0개인 테넌트가 서로 다른 이름으로 50개를 동시에 쏘면 50개 전부 COUNT=0을 읽는다
        // (이름 UNIQUE는 이름이 다르면 안 걸린다). IaC 병렬 생성이 그 형태다.
        //
        // 그래도 잠그지 않는다 — 임계가 60개인데 상한이 20이라 여유가 3배이고, 큐 생성은
        // 초당 수십 번 부르는 경로가 아니다. "테넌트당 N행"은 DB 제약으로 표현할 수 없고
        // (UNIQUE로 표현하려면 slot 컬럼·마이그레이션·재사용 로직이 붙는다), 분산 락은
        // 콜드패스에 과하다. **막는 대상이 악의가 아니라 오타이므로 상계가 유계면 족하다.**
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
