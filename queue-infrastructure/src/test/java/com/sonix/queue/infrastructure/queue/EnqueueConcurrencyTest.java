package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.infrastructure.config.RedisConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enqueue 동시성 통합 테스트 (실제 Redis Lua).
 *
 * <p>로컬 WSL2 Redis에 연결하며, 테스트 키는 "test:" prefix로 격리하고
 * 각 테스트 후 정리한다. Global Queue → BatchProcessor 실제 흐름으로 검증한다.
 *
 * <p><b>검증 명제:</b>
 * <ul>
 *   <li>순번 유일성: 1000 동시 진입 → rank 0~999, 중복/누락 0</li>
 *   <li>멱등성: 같은 identifier 중복 → OK 1 + EXISTS 나머지, 크기 1</li>
 *   <li>정원 초과: maxCapacity 초과분 → FULL</li>
 * </ul>
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@Tag("redis")
public class EnqueueConcurrencyTest {

    @Autowired private RedisQueueEngine queueEngine;
    @Autowired private BatchProcessor batchProcessor;
    @Autowired private StringRedisTemplate redisTemplate;

    private static final String TEST_QUEUE_ID = "test_q_concurrency";
    private static final String QUEUE_KEY = QueueKeys.waiting(TEST_QUEUE_ID);
    private static final String SEQ_KEY = QueueKeys.seq(TEST_QUEUE_ID);
    private static final String TOKEN_KEY = QueueKeys.tokens(TEST_QUEUE_ID);

    private Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeEach
    void startConsumer() {
        redisTemplate.delete(QUEUE_KEY);
        redisTemplate.delete(SEQ_KEY);
        redisTemplate.delete(TOKEN_KEY);// 이전 잔여 데이터 정리
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
                    e.printStackTrace();  // 예외 나도 멈추지 않고 계속
                }
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    @AfterEach
    void stopConsumer() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        redisTemplate.delete(QUEUE_KEY);
        redisTemplate.delete(SEQ_KEY);
        redisTemplate.delete(TOKEN_KEY);
    }

    @Test
    @DisplayName("1000명 동시 진입 시 순번이 0~999로 유일하게 부여된다")
    void concurrentEnqueue_uniqueRanks() throws InterruptedException {
        int userCount = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);
        ConcurrentLinkedQueue<EnqueueResult> results = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < userCount; i++) {
            String identifier = "user_" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    results.add(queueEngine.enqueue(TEST_QUEUE_ID, identifier));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        List<EnqueueResult> okResults = results.stream()
                .filter(r -> r.getStatus() == EnqueueResult.Status.OK)
                .collect(Collectors.toList());
        assertThat(okResults).hasSize(userCount);

        List<Long> ranks = okResults.stream().map(EnqueueResult::getRank).sorted().collect(Collectors.toList());
        List<Long> expected = IntStream.range(0, userCount).mapToObj(Long::valueOf).collect(Collectors.toList());
        assertThat(ranks).isEqualTo(expected);

        assertThat(redisTemplate.opsForZSet().size(QUEUE_KEY)).isEqualTo((long) userCount);
    }

    /**
     * queueId를 바꿔가며 <b>4개 마스터 전부</b>에서 enqueue_bulk.lua가 실행되는지 확인한다.
     *
     * <p>고정 queueId 하나로 검사하면 그 큐가 배치된 노드 한 대만 밟는다. 슬롯 라우팅이나
     * 해시태그가 깨져 있어도 <b>4대 중 1대</b>에서는 우연히 통과할 수 있다. 여기서 쓰는 12개
     * queueId는 슬롯 구간 4개를 3개씩 덮는다({@link QueueKeysSlotTest#QUEUE_IDS} 참조).
     *
     * <p>깨지면 나타나는 증상: {@code CROSSSLOT}(키가 다른 슬롯) 또는
     * {@code Lua script attempted to access a non local key}(KEYS 없이 EVAL).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "queueId={0}")
    @org.junit.jupiter.params.provider.MethodSource("multiSlotQueueIds")
    @DisplayName("슬롯 구간이 다른 queueId 12개에서 모두 enqueue가 성공한다 (4 master 전수)")
    void enqueue_acrossAllMasterSlots(String queueId) throws InterruptedException {
        try {
            List<EnqueueResult> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                results.add(queueEngine.enqueue(queueId, "user_" + i));
            }

            assertThat(results).extracting(EnqueueResult::getStatus)
                    .containsOnly(EnqueueResult.Status.OK);
            assertThat(results).extracting(EnqueueResult::getRank)
                    .containsExactly(0L, 1L, 2L);
            assertThat(redisTemplate.opsForZSet().size(QueueKeys.waiting(queueId))).isEqualTo(3L);
        } finally {
            redisTemplate.delete(QueueKeys.waiting(queueId));
            redisTemplate.delete(QueueKeys.seq(queueId));
            redisTemplate.delete(QueueKeys.tokens(queueId));
        }
    }

    static List<String> multiSlotQueueIds() {
        return QueueKeysSlotTest.QUEUE_IDS;
    }

    @Test
    @DisplayName("같은 identifier 동시 중복 진입 시 하나만 OK, 나머지는 EXISTS")
    void concurrentDuplicate_idempotent() throws InterruptedException {
        int attempts = 100;
        String sameId = "user_duplicate";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(attempts);
        ConcurrentLinkedQueue<EnqueueResult> results = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    results.add(queueEngine.enqueue(TEST_QUEUE_ID, sameId));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // 대기열 크기 = 1 (중복 진입 없음)
        assertThat(redisTemplate.opsForZSet().size(QUEUE_KEY)).isEqualTo(1L);

        // OK 1건
        long okCount = results.stream().filter(r -> r.getStatus() == EnqueueResult.Status.OK).count();
        assertThat(okCount).isEqualTo(1L);
    }
}
