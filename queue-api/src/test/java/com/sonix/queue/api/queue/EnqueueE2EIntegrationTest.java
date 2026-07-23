package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.queue.BatchProcessor;
import com.sonix.queue.infrastructure.queue.QueueKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Enqueue E2E 통합 테스트 (Tenant 생성 → Queue 생성 → 1,000 Enqueue).
 *
 * <p>실제 흐름 그대로 검증한다:
 * <pre>
 *   Tenant 생성/저장 → Queue 생성/저장(해당 Tenant 소유)
 *     → QueueEngineService.enqueue (소유권·ACTIVE 검증)
 *       → RedisQueueEngine (Global Queue offer + Future 대기)
 *         → BatchProcessor (drain → groupBy → enqueue_bulk.lua) → 실제 Redis
 * </pre>
 *
 * <p>로컬 WSL2 Redis(Sentinel)에 연결하며, 테스트 키는 매 테스트 정리한다.
 * BatchProcessor의 @Scheduled 는 테스트에서 활성화하지 않고 별도 consumer 스레드로 구동한다.
 *
 * <p><b>검증 명제:</b>
 * <ul>
 *   <li>Tenant/Queue 생성 후 1,000 동시 진입 → 전부 OK, rank 0~999 유일(중복·누락 0)</li>
 *   <li>ZSet 크기 == 1,000</li>
 *   <li>소유권 검증: 다른 Tenant id로 진입 시 QUEUE_NOT_OWNED</li>
 * </ul>
 */
@SpringBootTest(classes = EnqueueE2ETestConfig.class)
class EnqueueE2EIntegrationTest {

    @Autowired private QueueEngineService queueEngineService;
    @Autowired private BatchProcessor batchProcessor;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private QueueRepository queueRepository;

    private Long tenantId;
    private String queueId;
    private String queueKey;
    private String seqKey;
    private String tokenKey;

    private Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        // 1) Tenant 생성 → 저장(id 채번)
        Tenant tenant = tenantRepository.save(
                Tenant.create("owner+" + System.nanoTime() + "@corp.com", "hashed_pw", "테스트 테넌트"));
        tenantId = tenant.getId();

        // 2) Queue 생성 → 저장 (해당 Tenant 소유, ACTIVE)
        Queue queue = queueRepository.save(
                Queue.create(tenantId, "런칭 이벤트 대기열", 100_000, null, null));
        queueId = queue.getQueueId();

        queueKey = QueueKeys.waiting(queueId);
        seqKey = QueueKeys.seq(queueId);
        tokenKey = QueueKeys.tokens(queueId);
        redisTemplate.delete(queueKey);
        redisTemplate.delete(seqKey);
        redisTemplate.delete(tokenKey);

        // 3) BatchProcessor consumer 구동 (스케줄러 대체)
        running.set(true);
        consumerThread = new Thread(() -> {
            while (running.get()) {
                try {
                    batchProcessor.processBatches();
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    @AfterEach
    void tearDown() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        redisTemplate.delete(queueKey);
        redisTemplate.delete(seqKey);
        redisTemplate.delete(tokenKey);
    }

    @Test
    @DisplayName("Tenant·Queue 생성 후 1,000건 동시 Enqueue → 전부 OK, 순번 0~999 유일")
    void enqueue_1000_afterTenantAndQueueCreated() throws InterruptedException {
        int userCount = 1_000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);
        ConcurrentLinkedQueue<EnqueueResult> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 요청 1건당 가상 스레드 1개 → OS 스레드 고갈 없이 진짜 동시 출발 (CLAUDE.md 규칙)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < userCount; i++) {
            String identifier = "user_" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 실제 서비스 경유 (tenantId 소유권 + ACTIVE 검증 포함)
                    results.add(queueEngineService.enqueue(tenantId, queueId, identifier));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 모든 요청 완료 & 예외 없음
        assertThat(completed).as("1,000건 모두 60초 내 완료").isTrue();
        assertThat(errors).as("Enqueue 중 예외 없음").isEmpty();

        // 전부 신규 진입(OK) — 중복 identifier 없으므로 EXISTS/FULL 0건
        List<EnqueueResult> okResults = results.stream()
                .filter(r -> r.getStatus() == EnqueueResult.Status.OK)
                .collect(Collectors.toList());
        assertThat(okResults).as("OK 결과 1,000건").hasSize(userCount);

        // rank 0~999 유일 (중복·누락 0)
        List<Long> ranks = okResults.stream()
                .map(EnqueueResult::getRank)
                .sorted()
                .collect(Collectors.toList());
        List<Long> expected = IntStream.range(0, userCount)
                .mapToObj(Long::valueOf)
                .collect(Collectors.toList());
        assertThat(ranks).as("순번 0~999 유일하게 부여").isEqualTo(expected);

        // Redis ZSet 실제 크기 == 1,000
        Long zsetSize = redisTemplate.opsForZSet().size(queueKey);
        assertThat(zsetSize).as("Redis 대기열 크기 1,000").isEqualTo((long) userCount);
    }

    @Test
    @DisplayName("다른 Tenant id로 진입 시 소유권 검증 실패(QUEUE_NOT_OWNED)")
    void enqueue_withWrongTenant_throwsNotOwned() {
        Long otherTenantId = tenantId + 9_999;

        assertThatThrownBy(() -> queueEngineService.enqueue(otherTenantId, queueId, "intruder"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUEUE_NOT_OWNED);

        // 거부되었으므로 큐는 비어 있어야 한다
        Long zsetSize = redisTemplate.opsForZSet().size(queueKey);
        assertThat(zsetSize == null ? 0L : zsetSize).isZero();
    }
}
