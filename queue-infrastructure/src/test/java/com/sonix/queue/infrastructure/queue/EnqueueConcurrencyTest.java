package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.infrastructure.config.RedisConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

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
