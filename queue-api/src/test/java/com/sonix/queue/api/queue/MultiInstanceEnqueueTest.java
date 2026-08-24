package com.sonix.queue.api.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.queue.BatchProcessor;
import com.sonix.queue.infrastructure.queue.QueueKeys;
import com.sonix.queue.infrastructure.queue.RedisQueueEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수평 확장(WAS 3대) 인-JVM 시뮬레이션 통합 테스트.
 *
 * <p>한 테스트 JVM 안에서 {@link RedisQueueEngine}+{@link BatchProcessor}+{@link QueueEngineService}
 * 쌍을 3개 만들어 "WAS 3대"를 흉내낸다. 세 인스턴스는 각자 <b>독립된 JVM-로컬 Global Queue</b>와
 * <b>독립된 BatchProcessor</b>를 갖지만, <b>같은 Redis</b>를 공유한다.
 *
 * <p><b>검증 명제:</b> Global Queue가 인스턴스별로 분리되어 있어도, 순번 발급/중복 방지/정원의
 * 원자성은 Redis 단일 키(INCR seq, HSETNX tokens)에 있으므로 <b>3개 BatchProcessor가 같은 큐 키를
 * 동시에 때려도 정합성이 깨지지 않는다</b> (CLAUDE.md 동시성 원칙).
 *
 * <p><b>시나리오:</b> 5 tenant × 1 queue = 5 queue, 총 10,000 enqueue 를 (요청 i)
 * <ul>
 *   <li>queue = i % 5  (각 큐 정확히 2,000건)</li>
 *   <li>WAS   = i % 3  (세 인스턴스에 라운드로빈 분산)</li>
 * </ul>
 * 로 뿌린다. 기대: 각 큐 ZCARD == 2,000, rank 0~1999 유일, 전체 OK 10,000, 예외 0.
 */
@SpringBootTest(classes = EnqueueE2ETestConfig.class)
class MultiInstanceEnqueueTest {

    private static final int WAS_COUNT = 3;
    private static final int QUEUE_COUNT = 5;
    private static final int TOTAL_REQUESTS = 10_000;
    private static final int PER_QUEUE = TOTAL_REQUESTS / QUEUE_COUNT; // 2,000

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired @Qualifier("enqueueBulkScript") private RedisScript<List> enqueueBulkScript;
    @Autowired @Qualifier("pollVerifyScript") private RedisScript<Long> pollVerifyScript;
    @Autowired @Qualifier("admitScript") private RedisScript<List> admitScript;
    @Autowired @Qualifier("admitExpireScript") private RedisScript<List> admitExpireScript;
    @Autowired @Qualifier("inactiveExpireScript") private RedisScript<List> inactiveExpireScript;
    @Autowired @Qualifier("waitingExpireScript") private RedisScript<List> waitingExpireScript;
    @Autowired private QueueRepository queueRepository;
    @Autowired private TenantRepository tenantRepository;

    /** WAS 1대 = engine + batch + service 한 벌. */
    private record Was(RedisQueueEngine engine, BatchProcessor batch, QueueEngineService service) {}

    private final List<Was> instances = new ArrayList<>();
    private final List<Long> tenantIds = new ArrayList<>();
    private final List<String> queueIds = new ArrayList<>();

    private final List<Thread> consumers = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        // 5 tenant + 5 queue 생성 (tenant 1개당 queue 1개)
        for (int i = 0; i < QUEUE_COUNT; i++) {
            Tenant tenant = tenantRepository.save(
                    Tenant.create("tenant" + i + "+" + System.nanoTime() + "@corp.com", "pw", "tenant-" + i));
            Queue queue = queueRepository.save(
                    Queue.create(tenant.getId(), "queue-" + i, 1_000_000, null, null));
            tenantIds.add(tenant.getId());
            queueIds.add(queue.getQueueId());
            redisTemplate.delete(queueKey(queue.getQueueId()));
            redisTemplate.delete(seqKey(queue.getQueueId()));
            redisTemplate.delete(tokenKey(queue.getQueueId()));
        }

        // WAS 3대: 각자 독립 engine(=독립 Global Queue) + 독립 batch, 공유 repo/redis
        running.set(true);
        for (int w = 0; w < WAS_COUNT; w++) {
            RedisQueueEngine engine = new RedisQueueEngine(redisTemplate, enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript, inactiveExpireScript, waitingExpireScript);
            BatchProcessor batch = new BatchProcessor(engine, queueRepository);
            // Kafka 브로커 없이 Enqueue(Redis) 흐름만 검증 → no-op 발행자
            // enqueue 경로는 TokenRepository를 쓰지 않는다(admit/verify/complete 전용) → null
            QueueEngineService service = new QueueEngineService(queueRepository, null, engine, event -> { },
                    java.time.Clock.systemUTC());
            instances.add(new Was(engine, batch, service));

            Thread consumer = new Thread(() -> {
                while (running.get()) {
                    try {
                        batch.processBatches();
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, "batch-consumer-was-" + w);
            consumer.setDaemon(true);
            consumer.start();
            consumers.add(consumer);
        }
    }

    @AfterEach
    void tearDown() {
        running.set(false);
        consumers.forEach(Thread::interrupt);
        for (String queueId : queueIds) {
            redisTemplate.delete(queueKey(queueId));
            redisTemplate.delete(seqKey(queueId));
            redisTemplate.delete(tokenKey(queueId));
        }
    }

    @Test
    @DisplayName("WAS 3대 분산 투입 → 10,000건이 5개 큐에 정확히 2,000씩, 순번 중복 0")
    void enqueue_10000_across_3was_and_5queues() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(TOTAL_REQUESTS);
        ConcurrentLinkedQueue<EnqueueResult> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            int qIdx = i % QUEUE_COUNT;      // 어느 큐
            int wIdx = i % WAS_COUNT;        // 어느 WAS
            String identifier = "u_" + i;    // 전역 유일
            long tenantId = tenantIds.get(qIdx);
            String queueId = queueIds.get(qIdx);
            QueueEngineService service = instances.get(wIdx).service();

            executor.submit(() -> {
                try {
                    startLatch.await();
                    results.add(service.enqueue(tenantId, queueId, identifier));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("10,000건 모두 120초 내 완료").isTrue();
        assertThat(errors).as("Enqueue 중 예외 없음").isEmpty();

        // 전체 OK 10,000 (identifier 전역 유일 → EXISTS/FULL 0)
        long okCount = results.stream().filter(r -> r.getStatus() == EnqueueResult.Status.OK).count();
        assertThat(okCount).as("전체 OK 10,000건").isEqualTo(TOTAL_REQUESTS);

        // 큐별 검증: ZCARD == 2,000, rank 0~1999 유일
        List<Long> expectedRanks = IntStream.range(0, PER_QUEUE).mapToObj(Long::valueOf).toList();
        for (int q = 0; q < QUEUE_COUNT; q++) {
            final int qIdx = q;
            String queueId = queueIds.get(q);

            Long zsetSize = redisTemplate.opsForZSet().size(queueKey(queueId));
            assertThat(zsetSize).as("큐[%s] Redis 크기 2,000", queueId).isEqualTo((long) PER_QUEUE);

            List<Long> ranks = results.stream()
                    .filter(r -> r.getStatus() == EnqueueResult.Status.OK)
                    .filter(r -> belongsToQueue(r.getIdentifier(), qIdx))
                    .map(EnqueueResult::getRank)
                    .sorted()
                    .collect(Collectors.toList());
            assertThat(ranks).as("큐[%s] 순번 0~1999 유일", queueId).isEqualTo(expectedRanks);
        }

        // 3 WAS가 실제로 모두 일을 했는지 (한 인스턴스가 전부 처리한 게 아님) 확인
        Set<Long> seqKeysExist = queueIds.stream()
                .map(id -> redisTemplate.opsForValue().get(seqKey(id)))
                .filter(v -> v != null)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        assertThat(seqKeysExist).as("각 큐 seq 카운터가 2,000 이상까지 발급됨").allSatisfy(
                v -> assertThat(v).isGreaterThanOrEqualTo((long) PER_QUEUE));
    }

    /** identifier "u_{i}" 가 큐 인덱스 q 소속인지 (i % 5 == q). */
    private boolean belongsToQueue(String identifier, int q) {
        int i = Integer.parseInt(identifier.substring(2));
        return i % QUEUE_COUNT == q;
    }

    private String queueKey(String queueId) { return QueueKeys.waiting(queueId); }
    private String seqKey(String queueId) { return QueueKeys.seq(queueId); }
    private String tokenKey(String queueId) { return QueueKeys.tokens(queueId); }
}
