package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BatchProcessor 단위 테스트.
 *
 * <p>스케줄러 없이 processBatches()를 직접 호출하여 drain → groupBy → chunk 흐름을 검증한다.
 * Redis/Lua는 Mock으로 대체하고, 배치 처리 "로직"의 정확성에 집중한다.
 * (Lua 원자성/순번 유일성은 통합 테스트에서 실제 Redis로 검증)
 */
@ExtendWith(MockitoExtension.class)
public class BatchProcessorTest {
    @Mock
    private RedisQueueEngine queueEngine;

    @Mock
    private QueueRepository queueRepository;

    /**
     * 🪤 {@code @InjectMocks}를 쓰지 않는다 — 생성자에 primitive {@code boolean}이 있어
     * Mockito가 주입하지 못하고 클래스 전체가 {@code MockitoException}으로 죽는다(실측).
     * 게다가 캐시 여부는 테스트가 <b>명시적으로 통제해야 하는 변수</b>다.
     *
     * <p>기본은 캐시 <b>끔</b>이다. 기존 단정 대부분이 "틱마다 용량을 조회한다"를 전제로
     * 쓰여 있어, 켜면 그 전제가 조용히 바뀐다. 캐시 동작은 전용 테스트 둘이 따로 잠근다.
     */
    private BatchProcessor batchProcessor;

    @BeforeEach
    void setUp() {
        batchProcessor = new BatchProcessor(queueEngine, queueRepository, 0L);
    }

    /** 발급 시각 고정값. BatchProcessor가 Instant.now()로 만들어 넘기므로 stub은 any()로 받는다. */
    private static final Instant T0 = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    @DisplayName("빈 globalQueue면 아무 처리도 하지 않는다")
    void emptyQueue_doesNothing() {
        // given
        when(queueEngine.getGlobalQueue()).thenReturn(new ConcurrentLinkedQueue<>());

        // when
        batchProcessor.processBatches();

        // then
        verify(queueEngine, never()).executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class));
    }

    @Test
    @DisplayName("여러 queueId가 섞인 요청을 queueId별로 groupBy하여 각각 Bulk 실행한다")
    void groupsByQueueId_andExecutesBulkPerQueue() {
        // given: q_a 2건, q_b 1건이 섞인 globalQueue
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue a1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        PendingEnqueue a2 = new PendingEnqueue("q_a", "u2", "tok_u2");
        PendingEnqueue b1 = new PendingEnqueue("q_b", "u3", "tok_u3");
        global.offer(a1);
        global.offer(a2);
        global.offer(b1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));
        when(queueRepository.findByQueueId("q_b")).thenReturn(Optional.of(mockQueue(10000)));

        // parseBulkResult는 요청 batch와 같은 순서의 List를 반환한다.
        // 큐별로 결과 개수가 달라야 하므로 raw 반환값을 sentinel로 구분해 stub한다.
        List<Object> rawA = List.of("raw_a");
        List<Object> rawB = List.of("raw_b");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class))).thenReturn(rawA);
        when(queueEngine.executeBulkLua(eq("q_b"), anyList(), anyLong(), any(Instant.class))).thenReturn(rawB);
        when(queueEngine.parseBulkResult(rawA)).thenReturn(List.of(
                EnqueueResult.ok("u1", "tok_u1", 0, 1, 1, T0),
                EnqueueResult.ok("u2", "tok_u2", 1, 2, 2, T0)
        ));
        when(queueEngine.parseBulkResult(rawB)).thenReturn(List.of(
                EnqueueResult.ok("u3", "tok_u3", 0, 1, 1, T0)
        ));

        // when
        batchProcessor.processBatches();

        // then: queue별로 각각 1회씩 Bulk 실행
        verify(queueEngine).executeBulkLua(eq("q_a"), argThat(list -> list.size() == 2), anyLong(), any(Instant.class));
        verify(queueEngine).executeBulkLua(eq("q_b"), argThat(list -> list.size() == 1), anyLong(), any(Instant.class));
    }

    @Test
    @DisplayName("한 queue의 요청이 CHUNK_SIZE(500)를 넘으면 청크로 나눠 여러 번 Bulk 실행한다")
    void largeQueue_splitsIntoChunks() {
        // given: q_a에 1200건 → 500/500/200 = 3청크
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 1200; i++) {
            global.offer(new PendingEnqueue("q_a", "u" + i, "tok_u" + i));
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(100000)));
        // 청크마다 크기가 다르므로(500/500/200) 요청 크기에 맞춰 결과를 생성한다
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class))).thenAnswer(inv -> {
            List<PendingEnqueue> chunk = inv.getArgument(1);
            return chunk.stream().map(p -> (Object) p.getIdentifier()).toList();
        });
        when(queueEngine.parseBulkResult(anyList())).thenAnswer(inv -> {
            List<Object> raw = inv.getArgument(0);
            List<EnqueueResult> results = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                results.add(EnqueueResult.ok((String) raw.get(i), "tok_" + raw.get(i), i, i + 1, i + 1, T0));
            }
            return results;
        });

        // when
        batchProcessor.processBatches();

        // then: 1200건 → 3청크 (500, 500, 200)
        verify(queueEngine, times(3)).executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class));
    }

    @Test
    @DisplayName("Bulk 실행 중 예외가 나면 해당 청크의 모든 Future를 예외로 완료한다")
    void bulkFailure_failsAllPendingInChunk() {
        // given
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        global.offer(p1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenThrow(new RuntimeException("Redis down"));

        // when
        batchProcessor.processBatches();

        // then: Future가 예외로 완료됨
        assertThat(p1.getFuture()).isCompletedExceptionally();
    }

    @Test
    @DisplayName("같은 identifier가 한 청크에 여러 건이면 각 Future가 자기 결과를 받는다")
    void duplicateIdentifierInChunk_eachFutureGetsOwnResult() throws Exception {
        // given: 같은 identifier로 3건 동시 진입 → Lua는 OK 1 + EXISTS 2를 순서대로 반환
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "dup", "tok_dup1");
        PendingEnqueue p2 = new PendingEnqueue("q_a", "dup", "tok_dup2");
        PendingEnqueue p3 = new PendingEnqueue("q_a", "dup", "tok_dup3");
        global.offer(p1);
        global.offer(p2);
        global.offer(p3);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> raw = List.of("raw");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class))).thenReturn(raw);
        when(queueEngine.parseBulkResult(raw)).thenReturn(List.of(
                EnqueueResult.ok("dup", "tok_dup1", 0, 1, 1, T0),
                EnqueueResult.exists("dup", "tok_dup1", 0, 1, 1, T0),
                EnqueueResult.exists("dup", "tok_dup1", 0, 1, 1, T0)
        ));

        // when
        batchProcessor.processBatches();

        // then: identifier로 매칭하면 셋 다 EXISTS로 뭉개진다. 위치로 매칭해야 통과.
        assertThat(result(p1).getStatus()).isEqualTo(EnqueueResult.Status.OK);
        assertThat(result(p2).getStatus()).isEqualTo(EnqueueResult.Status.EXISTS);
        assertThat(result(p3).getStatus()).isEqualTo(EnqueueResult.Status.EXISTS);
    }

    @Test
    @DisplayName("결과 개수가 요청 개수와 다르면 청크 전체를 예외로 완료한다")
    void resultSizeMismatch_failsWholeChunk() {
        // given: 2건 요청했는데 결과는 1건
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        PendingEnqueue p2 = new PendingEnqueue("q_a", "u2", "tok_u2");
        global.offer(p1);
        global.offer(p2);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> raw = List.of("raw");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class))).thenReturn(raw);
        when(queueEngine.parseBulkResult(raw)).thenReturn(List.of(EnqueueResult.ok("u1", "tok_u1", 0, 1, 1, T0)));

        // when
        batchProcessor.processBatches();

        // then: 일부만 성공시키지 않고 전원 예외
        assertThat(p1.getFuture()).isCompletedExceptionally();
        assertThat(p2.getFuture()).isCompletedExceptionally();
    }

    @Test
    @DisplayName("종료 훅은 Global Queue에 남은 요청을 마지막으로 drain해 Future를 완결시킨다")
    void stop_drainsRemainingRequests() throws Exception {
        // given: 스케줄러가 이미 멈춘 상태에서 큐에 남아 있는 요청
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        global.offer(p1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> raw = List.of("raw");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong(), any(Instant.class))).thenReturn(raw);
        when(queueEngine.parseBulkResult(raw)).thenReturn(List.of(
                EnqueueResult.ok("u1", "tok_u1", 0, 1, 1, T0)
        ));

        // when
        batchProcessor.start();
        batchProcessor.stop();

        // then: 종료 사실을 엔진에 알리고, 남은 요청은 정상 결과로 응답된다(유실 0)
        verify(queueEngine).markShuttingDown();
        assertThat(batchProcessor.isRunning()).isFalse();
        assertThat(result(p1).getStatus()).isEqualTo(EnqueueResult.Status.OK);
        assertThat(global).isEmpty();
    }

    @Test
    @DisplayName("종료 drain 중 큐 조회가 실패하면 그 그룹은 매달리지 않고 예외로 완결된다")
    void stop_whenCapacityLookupFails_failsThatGroupWithoutHanging() {
        // given: 큐 조회 실패(예: 종료 중 DB 커넥션 정리)
        // 이 예외는 processQueueGroup의 catch에서 잡혀 failAllPending으로 끝나므로
        // stop()의 catch에는 도달하지 않는다. 검증 대상은 "그 그룹이 예외로 완결되는가"다.
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        global.offer(p1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.empty());

        // when
        batchProcessor.start();
        batchProcessor.stop();

        // then: 30초 타임아웃을 기다리게 두지 않고 즉시 실패로 응답
        assertThat(p1.getFuture()).isCompletedExceptionally();
        assertThat(global).isEmpty();
    }

    @Test
    @DisplayName("한 큐의 capacity 조회 실패가 같은 사이클의 다른 큐 요청까지 죽이지 않는다")
    void processBatches_groupFailureIsIsolatedFromOtherGroups() throws Exception {
        // given: 한 사이클에 큐 A(DB에 없음)와 큐 B(정상)가 섞여 있다.
        // A의 실패가 사이클 전체를 깨면, 이미 drain된 B의 요청은 다음 사이클에도 보이지 않아
        // 아무 결과 없이 사라진다(Global Queue에서 이미 빠져나왔으므로).
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue a1 = new PendingEnqueue("q_a", "u1", "tok_u1");
        PendingEnqueue b1 = new PendingEnqueue("q_b", "u2", "tok_u2");
        global.offer(a1);
        global.offer(b1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.empty());
        when(queueRepository.findByQueueId("q_b")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> rawB = List.of("raw_b");
        when(queueEngine.executeBulkLua(eq("q_b"), anyList(), anyLong(), any(Instant.class))).thenReturn(rawB);
        when(queueEngine.parseBulkResult(rawB)).thenReturn(List.of(
                EnqueueResult.ok("u2", "tok_u2", 0, 1, 1, T0)
        ));

        // when
        batchProcessor.processBatches();

        // then: A만 실패하고 B는 정상 결과를 받는다
        assertThat(a1.getFuture()).isCompletedExceptionally();
        assertThat(result(b1).getStatus()).isEqualTo(EnqueueResult.Status.OK);
    }

    @Test
    @DisplayName("종료 훅은 웹 graceful shutdown 단계보다 먼저 실행되는 phase를 갖는다")
    void phase_runsBeforeWebGracefulShutdown() {
        // 내림차순으로 stop되므로 phase가 클수록 먼저다.
        // 웹 계층이 in-flight 요청을 기다리기 시작하기 전에 drain이 끝나야 응답을 보낼 수 있다.
        assertThat(batchProcessor.getPhase())
                .isGreaterThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }

    private EnqueueResult result(PendingEnqueue pending) throws ExecutionException, InterruptedException {
        assertThat(pending.getFuture()).isCompleted();
        return pending.getFuture().get();
    }

    private Queue mockQueue(int maxCapacity) {
        return Queue.reconstruct(
                1L, "q_a", 1L, "테스트큐", maxCapacity,7200, 300,
                com.sonix.queue.domain.queue.QueueStatus.ACTIVE,
                java.time.LocalDateTime.now(), null
        );
    }

    @Test
    @DisplayName("🔴 용량 캐시가 켜지면 같은 큐의 DB 조회는 틱을 넘겨도 1회뿐이다")
    void capacityCache_hitsDbOncePerQueue() {
        // 이 단정이 지키는 것: 틱마다 큐 수만큼 DB를 치던 비용. 실측 A/B(큐 20개·2,000 rps)에서
        // 캐시를 끄면 p99가 40.02 → 73.95ms로 오르고 4판 전부 SLO(50ms)를 넘겼다.
        // 캐시가 조용히 무력화되면 그 회귀가 지연으로만 나타나고 아무 테스트도 빨개지지 않는다.
        BatchProcessor cached = new BatchProcessor(queueEngine, queueRepository, 30_000L);
        when(queueRepository.findByQueueId("q_a"))
                .thenReturn(Optional.of(mockQueue(100)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenReturn(List.of(List.of("OK", 1L, 1L)));

        for (int tick = 0; tick < 3; tick++) {
            ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
            global.offer(new PendingEnqueue("q_a", "u" + tick, "tok_u" + tick));
            when(queueEngine.getGlobalQueue()).thenReturn(global);
            cached.processBatches();
        }

        verify(queueEngine, times(3)).executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class));
        verify(queueRepository, times(1)).findByQueueId("q_a");   // ← 3틱인데 DB는 1회
    }

    @Test
    @DisplayName("🔴 TTL이 지나면 다시 읽는다 — 장애 중 정원 확대가 반영되는 근거다")
    void capacityCache_expiresAfterTtl() throws Exception {
        // 이 단정이 없으면 만료가 조용히 죽어도 아무도 모른다. 그 결과가 나쁘다 —
        // 운영 런북이 QUEUE_FULL 대응으로 `UPDATE queues SET max_capacity`를 지시하는데,
        // 만료가 없으면 그 UPDATE가 무음으로 실패하고 회복 수단이 전 인스턴스 재기동뿐이다.
        BatchProcessor shortTtl = new BatchProcessor(queueEngine, queueRepository, 1L);   // 1ms
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(100)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenReturn(List.of(List.of("OK", 1L, 1L)));

        for (int tick = 0; tick < 2; tick++) {
            ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
            global.offer(new PendingEnqueue("q_a", "u" + tick, "tok_u" + tick));
            when(queueEngine.getGlobalQueue()).thenReturn(global);
            shortTtl.processBatches();
            Thread.sleep(5);            // TTL(1ms)보다 충분히 길다
        }

        verify(queueRepository, times(2)).findByQueueId("q_a");   // 만료됐으므로 두 번
    }

    @Test
    @DisplayName("🔴 캐시 키는 queueId다 — 정원이 다른 큐가 서로의 값을 쓰지 않는다")
    void capacityCache_isKeyedByQueueId() {
        // 이 단정이 없으면 키가 무너져도(예: 상수 키) 통합 테스트가 통과한다 —
        // 거기 큐들은 정원이 전부 같아서 첫 큐의 값을 나눠 써도 결과가 같기 때문이다(실측).
        // 운영에서 정원이 다른 큐가 섞이면 과수용 또는 조기 429가 된다.
        BatchProcessor cached = new BatchProcessor(queueEngine, queueRepository, 30_000L);
        when(queueRepository.findByQueueId("q_small")).thenReturn(Optional.of(mockQueue(10)));
        when(queueRepository.findByQueueId("q_big")).thenReturn(Optional.of(mockQueue(9_999)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenReturn(List.of(List.of("OK", 1L, 1L)));

        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        global.offer(new PendingEnqueue("q_small", "u1", "tok_u1"));
        global.offer(new PendingEnqueue("q_big", "u2", "tok_u2"));
        when(queueEngine.getGlobalQueue()).thenReturn(global);

        cached.processBatches();

        ArgumentCaptor<Long> caps = ArgumentCaptor.forClass(Long.class);
        verify(queueEngine, times(2))
                .executeBulkLua(anyString(), anyList(), caps.capture(), any(Instant.class));
        assertThat(caps.getAllValues()).containsExactlyInAnyOrder(10L, 9_999L);
    }

    @Test
    @DisplayName("🔴 프로퍼티가 없으면 캐시는 켜져 있다 — 기본값 자체를 잠근다")
    void capacityCache_defaultsToEnabled() {
        // 🔴 이것이 없으면 @Value의 :true 리터럴이 :false로 되돌아가도 전 스위트가 초록이다(실측).
        //    모든 다른 테스트가 boolean을 명시해 넘기고, yml에도 이 키가 정의돼 있지 않아
        //    운영 동작이 소스의 리터럴 하나에만 달려 있다. 회귀는 p99 40 → 74ms로만 나타난다.
        new ApplicationContextRunner()
                .withBean(RedisQueueEngine.class, () -> queueEngine)
                .withBean(QueueRepository.class, () -> queueRepository)
                .withUserConfiguration(BatchProcessor.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BatchProcessor fromContext = context.getBean(BatchProcessor.class);

                    when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(100)));
                    when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                            .thenReturn(List.of(List.of("OK", 1L, 1L)));
                    for (int tick = 0; tick < 2; tick++) {
                        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
                        global.offer(new PendingEnqueue("q_a", "u" + tick, "tok_u" + tick));
                        when(queueEngine.getGlobalQueue()).thenReturn(global);
                        fromContext.processBatches();
                    }
                    verify(queueRepository, times(1)).findByQueueId("q_a");
                });
    }

    @Test
    @DisplayName("용량 캐시를 끄면 틱마다 DB를 친다 — 반사실")
    void capacityCacheOff_hitsDbEveryTick() {
        BatchProcessor uncached = new BatchProcessor(queueEngine, queueRepository, 0L);
        when(queueRepository.findByQueueId("q_a"))
                .thenReturn(Optional.of(mockQueue(100)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenReturn(List.of(List.of("OK", 1L, 1L)));

        for (int tick = 0; tick < 3; tick++) {
            ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
            global.offer(new PendingEnqueue("q_a", "u" + tick, "tok_u" + tick));
            when(queueEngine.getGlobalQueue()).thenReturn(global);
            uncached.processBatches();
        }

        verify(queueRepository, times(3)).findByQueueId("q_a");
    }
}
