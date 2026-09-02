package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * <b>Redis가 응답하지 못하는 상태에서 SIGTERM이 왔을 때</b> {@code stop()}의 실제 상한 검증.
 *
 * <p><b>왜 실제 Redis로 재현하지 않는가:</b> 이 시나리오를 실 Redis로 만들려면
 * {@code DEBUG SLEEP}이나 포트 차단으로 <b>서버 전체</b>를 멈춰야 하는데, 6379는 다른
 * 에이전트와 공유하는 인프라라 그들의 검증을 통째로 깨뜨린다. 대신 Redis 호출 지점을
 * 스텁으로 바꿔 "커맨드가 commandTimeout(5s)까지 매달렸다가 예외로 끝난다"는 동작을
 * 결정적으로 재현한다. commandTimeout 5s가 <b>실제로 걸려 있는지</b>는
 * {@link com.sonix.queue.infrastructure.config.RedisCommandTimeoutIntegrationTest}가
 * 실 Redis에 붙어 따로 확인한다.
 *
 * <p><b>고정하려는 계약:</b> 데드라인 검사는 청크 <b>사이</b>에만 있으므로, 검사를 통과해
 * 이미 시작된 커맨드 1회는 끝까지 기다린다. 따라서 최악은
 * {@code SHUTDOWN_DRAIN_TIMEOUT_MS(5s) + commandTimeout(5s) ≈ 10s}이며,
 * <b>5s가 아니다</b>. 이 테스트는 그 10s가 실재함(하한)과 넘지 않음(상한)을 동시에 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class BatchProcessorShutdownWithSlowRedisTest {

    @Mock
    private RedisQueueEngine queueEngine;

    @Mock
    private QueueRepository queueRepository;

    /**
     * 🪤 {@code @InjectMocks}를 쓰지 않는다 — 생성자에 primitive {@code boolean}이 있어
     * Mockito가 주입하지 못하고 클래스 전체가 {@code MockitoException}으로 죽는다(실측).
     * 이 테스트가 재는 것은 종료 경로이므로 캐시는 <b>끈다</b> — 켜면 그룹마다 DB를 친다는
     * 이 클래스의 전제가 바뀐다.
     */
    private BatchProcessor batchProcessor;

    @BeforeEach
    void setUpProcessor() {
        batchProcessor = new BatchProcessor(queueEngine, queueRepository, 0L);
    }

    @Test
    @DisplayName("데드라인 직전에 시작된 Redis 커맨드가 commandTimeout(5s)까지 매달려도 stop()은 10초대에서 끝난다")
    void stop_whenRedisHangsRightBeforeDeadline_boundedByDrainTimeoutPlusCommandTimeout() {
        // given: 12,000건. 청크(500)당 1,200ms가 걸리다가, 데드라인 직전(t≈4,800ms)에 시작된
        // 청크에서 Redis가 응답을 멈춘다 → commandTimeout 5s 뒤 예외.
        int total = 12_000;
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        List<PendingEnqueue> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            PendingEnqueue p = new PendingEnqueue("q_test_slowredis", "u" + i, "tok_" + i);
            all.add(p);
            global.offer(p);
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_test_slowredis")).thenReturn(Optional.of(activeQueue()));

        AtomicInteger chunkNo = new AtomicInteger();
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> {
                    // 1~4번째 청크: 1,200ms → 5번째 청크는 t≈4,800ms에 시작(데드라인 5,000ms 통과)
                    if (chunkNo.incrementAndGet() < 5) {
                        Thread.sleep(1_200);
                        return new ArrayList<>((List<?>) inv.getArgument(1));
                    }
                    // Redis 무응답: Lettuce commandTimeout(5s)까지 기다렸다 예외
                    Thread.sleep(5_000);
                    throw new QueryTimeoutException("Redis command timed out after 5s");
                });
        when(queueEngine.parseBulkResult(anyList()))
                .thenAnswer(inv -> BulkEcho.echo(inv.getArgument(0)));

        // when
        batchProcessor.start();
        long t0 = System.nanoTime();
        batchProcessor.stop();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // then
        // (1) 상한: 5s(데드라인) + 5s(이미 시작된 커맨드 1회) + 여유 1s
        assertThat(elapsedMs).isLessThan(11_000L);
        // (2) 하한: 5s로는 안 끝난다. "drain 상한 = 5s"라고 읽으면 틀린다는 것을 명시적으로 고정한다.
        //     (데드라인 직전에 시작된 커맨드는 끊을 수 없다 — stop()이 동기이기 때문)
        assertThat(elapsedMs).isGreaterThan(9_000L);
        // (3) 무응답이어도 아무도 매달리지 않는다: 큐는 비고 모든 Future가 완결된다
        assertThat(global).isEmpty();
        // 카운트로 본다. allSatisfy는 실패 건마다 메시지를 조립하는데 all이 수천 건이라
        // 부분 실패 시 그 문자열이 Gradle daemon을 OOM으로 죽인다(실측 재현).
        assertThat(all.stream().filter(p -> p.getFuture().isDone()).count()).isEqualTo(all.size());

        long ok = all.stream().filter(p -> !p.getFuture().isCompletedExceptionally()).count();
        System.out.printf("[slow-redis] elapsed=%dms, ok=%d, failed=%d, chunks=%d%n",
                elapsedMs, ok, total - ok, chunkNo.get());
    }

    @Test
    @DisplayName("첫 청크부터 Redis가 무응답이어도 stop()은 commandTimeout 한 번(≈5s)만 물고 끝난다")
    void stop_whenRedisHangsFromFirstChunk_paysCommandTimeoutOnce() {
        int total = 3_000;
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        List<PendingEnqueue> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            PendingEnqueue p = new PendingEnqueue("q_test_deadredis", "u" + i, "tok_" + i);
            all.add(p);
            global.offer(p);
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_test_deadredis")).thenReturn(Optional.of(activeQueue()));

        AtomicInteger calls = new AtomicInteger();
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    Thread.sleep(5_000);
                    throw new QueryTimeoutException("Redis command timed out after 5s");
                });

        batchProcessor.start();
        long t0 = System.nanoTime();
        batchProcessor.stop();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 6개 청크(3,000/500)가 각각 5s를 물면 30s다. 데드라인이 청크 루프 안에 있으므로
        // 실제로 대가를 치르는 커맨드는 1회뿐이어야 한다.
        assertThat(calls.get()).isEqualTo(1);
        assertThat(elapsedMs).isLessThan(6_500L);
        assertThat(global).isEmpty();
        // 카운트로 본다(위 slow-redis 주석과 같은 이유 — 부분 실패 시 OOM).
        assertThat(all.stream().filter(p -> p.getFuture().isCompletedExceptionally()).count())
                .isEqualTo(all.size());
        System.out.printf("[dead-redis] elapsed=%dms, luaCalls=%d%n", elapsedMs, calls.get());
    }

    /**
     * <b>느린 것이 Redis가 아니라 DB일 때</b>의 회귀 방지.
     *
     * <p>{@code getMaxCapacity}(캐시 미스 시 {@code findByQueueId})는 JDBC
     * {@code socketTimeout} 미설정(Connector/J 기본 0 = 무기한)이라 <b>시한이 없는 호출</b>이다.
     * 데드라인 검사가 청크 루프에만 있으면 큐 그룹마다 이 호출을 한 번씩 물어
     * {@code stop()} 경과가 그룹 수에 <b>선형 비례</b>한다(실측: DB 3s 가정 · 그룹 3개 → 9,016ms).
     * 그룹 진입 시 데드라인 검사가 그 호출을 최대 2회로 묶는다
     * (§75 이중 라우팅 이후 {@code routeForWrite}의 {@code redis_cluster_no} 조회가 더해졌다.
     *  그 조회는 (WAS, queueId)당 평생 1회, {@code getMaxCapacity}는 <b>TTL당 1회</b>라 상각된다.
     *  🪤 그래도 <b>안전의 근거는 상각이 아니라 데드라인 검사</b>다({@code processQueueGroup} 진입부).
     *  TTL 만료가 겹친 순간 SIGTERM이 오면 여러 그룹이 동시에 미스가 될 수 있다.
     *  이 테스트는 그룹마다 <b>다른 queueId</b>를 써서 캐시 히트가 구조적으로 불가능하게 두었다 —
     *  그래야 "그룹 수에 비례하지 않는다"를 캐시와 무관하게 잰다).
     *
     * <p>이 테스트가 고정하는 것은 "빠르다"가 아니라 <b>"그룹 수에 비례하지 않는다"</b>다.
     * {@code processQueueGroup} 맨 위의 데드라인 검사를 제거하면 3그룹 ≈9s / 5그룹 ≈15s가 되어 깨진다.
     * (1회의 <b>길이</b>는 여전히 무한이며, 그건 이 테스트가 보장하지 못한다 — socketTimeout의 몫)
     */
    @Test
    @DisplayName("느린 DB 조회(getMaxCapacity)가 있어도 stop() 경과는 큐 그룹 수에 비례하지 않는다")
    void stop_elapsedDoesNotScaleWithQueueGroupCount() {
        long threeGroups = measureStopWithSlowCapacityLookup(3);
        long fiveGroups = measureStopWithSlowCapacityLookup(5);

        System.out.printf("[slow-db] 3 groups=%dms, 5 groups=%dms, delta=%dms%n",
                threeGroups, fiveGroups, fiveGroups - threeGroups);

        // 그룹이 2개 늘어도 느린 DB 조회(3s) 1회분조차 늘지 않아야 한다.
        // (검사를 제거하면 delta ≈ 6,000ms)
        assertThat(fiveGroups - threeGroups).isLessThan(1_500L);
        // 상한 = 데드라인 5s + 검사를 통과해 이미 시작된 DB 조회 1회(스텁 3s) ≈ 6s, 여유 3s.
        // (검사를 제거하면 3그룹 9,016ms / 5그룹 ≈15,000ms로 여기서도 깨진다)
        assertThat(fiveGroups).isLessThan(9_000L);
    }

    /** 그룹 수만 바꿔 종료 drain 경과(ms)를 잰다. DB 조회 1회 = 3s로 스텁. */
    private long measureStopWithSlowCapacityLookup(int groupCount) {
        RedisQueueEngine engine = org.mockito.Mockito.mock(RedisQueueEngine.class);
        QueueRepository repository = org.mockito.Mockito.mock(QueueRepository.class);
        BatchProcessor processor = new BatchProcessor(engine, repository, 30_000L);

        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        List<PendingEnqueue> all = new ArrayList<>();
        for (int g = 0; g < groupCount; g++) {
            for (int i = 0; i < 10; i++) {
                PendingEnqueue p = new PendingEnqueue("q_test_slowdb_" + g, "u" + i, "tok_" + g + "_" + i);
                all.add(p);
                global.offer(p);
            }
        }

        when(engine.getGlobalQueue()).thenReturn(global);
        when(repository.findByQueueId(anyString())).thenAnswer(inv -> {
            Thread.sleep(3_000);   // 응답이 느린(그러나 결국은 오는) MySQL. 실제로는 상한이 없다.
            return Optional.of(activeQueue());
        });
        when(engine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> new ArrayList<>((List<?>) inv.getArgument(1)));
        when(engine.parseBulkResult(anyList()))
                .thenAnswer(inv -> BulkEcho.echo(inv.getArgument(0)));

        processor.start();
        long t0 = System.nanoTime();
        processor.stop();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 어느 경로로 끝나든 매달리는 요청은 없다.
        assertThat(global).isEmpty();
        assertThat(all).allSatisfy(p -> assertThat(p.getFuture()).isDone());
        return elapsedMs;
    }

    private Queue activeQueue() {
        return Queue.reconstruct(
                1L, "q_test_slowredis", 1L, "느린 Redis 테스트큐", 2_000_000, 7200, 300,
                QueueStatus.ACTIVE, LocalDateTime.now(), null
        );
    }

    /** 청크를 그대로 OK 결과로 되돌려주는 헬퍼 (위치 계약 유지). */
    static final class BulkEcho {
        @SuppressWarnings("unchecked")
        static List<com.sonix.queue.domain.queue.EnqueueResult> echo(Object raw) {
            List<PendingEnqueue> chunk = (List<PendingEnqueue>) raw;
            List<com.sonix.queue.domain.queue.EnqueueResult> out = new ArrayList<>(chunk.size());
            long seq = 0;
            for (PendingEnqueue p : chunk) {
                out.add(com.sonix.queue.domain.queue.EnqueueResult.ok(
                        p.getIdentifier(), p.getTokenId(), seq, chunk.size(), ++seq,
                        Instant.parse("2026-08-11T00:00:00Z")));
            }
            return out;
        }
    }
}
