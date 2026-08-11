package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Enqueue 500건 시간·메모리 실측 벤치마크 (실제 Redis Lua).
 *
 * <p>기존 {@link EnqueueConcurrencyTest}는 <b>정확성</b>(순번 유일성/멱등성)만 검증한다.
 * 이 클래스는 그와 별개로 <b>성능(지연/할당량)</b>을 측정한다. 문서에 남아있던
 * "500배 효율"은 왕복 횟수 산술 계산일 뿐 실측값이 아니었기에, 실제 수치를 남긴다.
 *
 * <p>두 경로 × 두 형상(One Queue / Multi Queue)을 잰다:
 * <ul>
 *   <li><b>(A) 배치 전체 경로</b>: {@link BatchProcessor#processBatches()} —
 *       Global Queue drain → groupBy → 청크 → Bulk Lua → Future 완료까지</li>
 *   <li><b>(B) 순수 EVAL 경로</b>: {@link RedisQueueEngine#executeBulkLua} 단독 —
 *       워밍업 후 반복 측정하여 p50/p90/p99/max 지연 분포</li>
 * </ul>
 *
 * <p><b>메모리:</b> 측정 구간이 호출 스레드에서 동기 실행되므로
 * {@code ThreadMXBean.getThreadAllocatedBytes()}(스레드 누적 할당 바이트)를 쓴다.
 * {@code Runtime} 힙 delta는 GC 타이밍·다른 스레드 할당에 오염되지만, 스레드 할당 바이트는
 * 그 구간 동안 이 스레드가 새로 할당한 바이트만 정확히 집계한다(회수량은 반영 안 됨 — 순수 할당 압력 지표).
 *
 * <p><b>실행 (기본 {@code ./gradlew test}에서는 제외):</b>
 * <pre>
 *   BENCH=true ./gradlew :queue-infrastructure:test --tests "*EnqueueBenchmarkTest" --info
 * </pre>
 * Redis Sentinel(26379~26381)이 떠 있어야 한다.
 */
@SpringBootTest(classes = QueueEngineRedisTestConfig.class)
@EnabledIfEnvironmentVariable(named = "BENCH", matches = "true")
public class EnqueueBenchmarkTest {

    @Autowired private RedisQueueEngine queueEngine;
    @Autowired private BatchProcessor batchProcessor;
    @Autowired private StringRedisTemplate redisTemplate;

    /** 측정 대상 건수 (CHUNK_SIZE=500 → One Queue는 정확히 1 청크 = 1 EVAL). */
    private static final int TOTAL = 500;

    /** Multi Queue 케이스에서 나눌 큐 개수 (500 / 5 = 큐당 100건 → 5 EVAL). */
    private static final int MULTI_QUEUES = 5;

    private static final int WARMUP = 20;
    private static final int ITERATIONS = 100;

    /** 이 벤치가 쓰는 큐 id 접두사 — @AfterEach 대신 각 케이스가 직접 정리한다. */
    private static final String Q_ONE = "bench_one";
    private static final String Q_MULTI_PREFIX = "bench_multi_";

    private static final long CAPACITY = 1_000_000L; // FULL 안 나도록 충분히 크게

    private final com.sun.management.ThreadMXBean threadMx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    // ────────────────────────────────────────────────────────────────────────
    // (B) 순수 EVAL 경로 — executeBulkLua 단독
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(B) One Queue 500건 - 순수 Bulk Lua EVAL 지연/할당 분포")
    void benchB_pureEval_oneQueue() {
        List<String> ids = ids(TOTAL);

        Runnable setup = () -> resetQueue(Q_ONE);
        Runnable timed = () -> {
            List<PendingEnqueue> batch = pendings(Q_ONE, ids);
            List<Object> raw = queueEngine.executeBulkLua(Q_ONE, batch, CAPACITY, Instant.now());
            List<EnqueueResult> parsed = queueEngine.parseBulkResult(raw);
            if (parsed.size() != TOTAL) throw new IllegalStateException("size " + parsed.size());
        };

        Sample s = measure("(B) One Queue  500건 / 1 EVAL ", setup, timed);
        resetQueue(Q_ONE);
        print(s);
    }

    @Test
    @DisplayName("(B) Multi Queue 500건 - 큐 5개 분산 Bulk Lua EVAL 지연/할당 분포")
    void benchB_pureEval_multiQueue() {
        int per = TOTAL / MULTI_QUEUES;
        List<String> ids = ids(per);

        Runnable setup = () -> {
            for (int q = 0; q < MULTI_QUEUES; q++) resetQueue(Q_MULTI_PREFIX + q);
        };
        Runnable timed = () -> {
            for (int q = 0; q < MULTI_QUEUES; q++) {
                String queueId = Q_MULTI_PREFIX + q;
                List<PendingEnqueue> batch = pendings(queueId, ids);
                List<Object> raw = queueEngine.executeBulkLua(queueId, batch, CAPACITY, Instant.now());
                queueEngine.parseBulkResult(raw);
            }
        };

        Sample s = measure("(B) Multi Queue 500건 / 5 EVAL ", setup, timed);
        for (int q = 0; q < MULTI_QUEUES; q++) resetQueue(Q_MULTI_PREFIX + q);
        print(s);
    }

    // ────────────────────────────────────────────────────────────────────────
    // (A) 배치 전체 경로 — BatchProcessor.processBatches()
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(A) One Queue 500건 - BatchProcessor 전체 경로 지연/할당 분포")
    void benchA_batchPath_oneQueue() {
        List<String> ids = ids(TOTAL);

        Runnable setup = () -> {
            resetQueue(Q_ONE);
            for (String id : ids) {
                queueEngine.getGlobalQueue().offer(new PendingEnqueue(Q_ONE, id, "tok_" + id));
            }
        };
        // drain + groupBy + chunk + Bulk Lua + Future 완료
        Runnable timed = () -> batchProcessor.processBatches();

        Sample s = measure("(A) One Queue  500건 batch  ", setup, timed);
        resetQueue(Q_ONE);
        print(s);
    }

    @Test
    @DisplayName("(A) Multi Queue 500건 - BatchProcessor 전체 경로(5큐 groupBy) 지연/할당 분포")
    void benchA_batchPath_multiQueue() {
        int per = TOTAL / MULTI_QUEUES;

        Runnable setup = () -> {
            for (int q = 0; q < MULTI_QUEUES; q++) resetQueue(Q_MULTI_PREFIX + q);
            // 여러 큐로 인터리브해서 offer → drain 후 groupBy가 실제로 갈라내도록
            for (int i = 0; i < per; i++) {
                for (int q = 0; q < MULTI_QUEUES; q++) {
                    queueEngine.getGlobalQueue()
                            .offer(new PendingEnqueue(Q_MULTI_PREFIX + q, "user_" + i, "tok_" + q + "_" + i));
                }
            }
        };
        Runnable timed = () -> batchProcessor.processBatches();

        Sample s = measure("(A) Multi Queue 500건 batch  ", setup, timed);
        for (int q = 0; q < MULTI_QUEUES; q++) resetQueue(Q_MULTI_PREFIX + q);
        print(s);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 측정 엔진
    // ────────────────────────────────────────────────────────────────────────

    private Sample measure(String label, Runnable setup, Runnable timed) {
        for (int i = 0; i < WARMUP; i++) { // JIT 워밍업 + Lua SCRIPT LOAD 캐시
            setup.run();
            timed.run();
        }

        long[] latencyNanos = new long[ITERATIONS];
        long[] allocBytes = new long[ITERATIONS];
        long tid = Thread.currentThread().threadId();

        for (int i = 0; i < ITERATIONS; i++) {
            setup.run(); // 큐 리셋/offer는 측정 구간 밖 (순수 Enqueue 비용만 계측)

            long a0 = threadMx.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();

            timed.run();

            long t1 = System.nanoTime();
            long a1 = threadMx.getThreadAllocatedBytes(tid);

            latencyNanos[i] = t1 - t0;
            allocBytes[i] = a1 - a0;
        }
        return new Sample(label, latencyNanos, allocBytes);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────────────────

    private List<String> ids(int n) {
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add("user_" + i);
        return ids;
    }

    private List<PendingEnqueue> pendings(String queueId, List<String> ids) {
        List<PendingEnqueue> batch = new ArrayList<>(ids.size());
        for (String id : ids) batch.add(new PendingEnqueue(queueId, id, "tok_" + id));
        return batch;
    }

    private void resetQueue(String queueId) {
        redisTemplate.delete(QueueKeys.waiting(queueId));
        redisTemplate.delete(QueueKeys.seq(queueId));
        redisTemplate.delete(QueueKeys.tokens(queueId));
    }

    private record Sample(String label, long[] latencyNanos, long[] allocBytes) {}

    private void print(Sample s) {
        long[] lat = s.latencyNanos().clone();
        long[] alloc = s.allocBytes().clone();
        Arrays.sort(lat);
        Arrays.sort(alloc);

        double avgMs = Arrays.stream(lat).average().orElse(0) / 1_000_000.0;
        double avgAllocKb = Arrays.stream(alloc).average().orElse(0) / 1024.0;

        System.out.printf("%n┌─ %s (N=%d, warmup=%d, TOTAL=%d건)%n", s.label(), ITERATIONS, WARMUP, TOTAL);
        System.out.printf("│  지연  avg=%.3f ms  p50=%.3f  p90=%.3f  p99=%.3f  max=%.3f ms%n",
                avgMs, ms(pct(lat, 50)), ms(pct(lat, 90)), ms(pct(lat, 99)), ms(lat[lat.length - 1]));
        System.out.printf("│  할당  avg=%.1f KB  p50=%.1f  p99=%.1f KB  (스레드 누적 할당 바이트)%n",
                avgAllocKb, kb(pct(alloc, 50)), kb(pct(alloc, 99)));
        System.out.printf("│  1건당 avg=%.4f ms / %.3f KB%n", avgMs / TOTAL, avgAllocKb / TOTAL);
        System.out.println("└────────────────────────────────────────────────────");
    }

    private static long pct(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private static double kb(long bytes) { return bytes / 1024.0; }
}
