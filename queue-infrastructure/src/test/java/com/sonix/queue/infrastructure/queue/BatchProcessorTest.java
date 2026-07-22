package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private BatchProcessor batchProcessor;

    @Test
    @DisplayName("빈 globalQueue면 아무 처리도 하지 않는다")
    void emptyQueue_doesNothing() {
        // given
        when(queueEngine.getGlobalQueue()).thenReturn(new ConcurrentLinkedQueue<>());

        // when
        batchProcessor.processBatches();

        // then
        verify(queueEngine, never()).executeBulkLua(anyString(), anyList(), anyLong());
    }

    @Test
    @DisplayName("여러 queueId가 섞인 요청을 queueId별로 groupBy하여 각각 Bulk 실행한다")
    void groupsByQueueId_andExecutesBulkPerQueue() {
        // given: q_a 2건, q_b 1건이 섞인 globalQueue
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue a1 = new PendingEnqueue("q_a", "u1");
        PendingEnqueue a2 = new PendingEnqueue("q_a", "u2");
        PendingEnqueue b1 = new PendingEnqueue("q_b", "u3");
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
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong())).thenReturn(rawA);
        when(queueEngine.executeBulkLua(eq("q_b"), anyList(), anyLong())).thenReturn(rawB);
        when(queueEngine.parseBulkResult(rawA)).thenReturn(List.of(
                EnqueueResult.ok("u1", 0, 1),
                EnqueueResult.ok("u2", 1, 2)
        ));
        when(queueEngine.parseBulkResult(rawB)).thenReturn(List.of(
                EnqueueResult.ok("u3", 0, 1)
        ));

        // when
        batchProcessor.processBatches();

        // then: queue별로 각각 1회씩 Bulk 실행
        verify(queueEngine).executeBulkLua(eq("q_a"), argThat(list -> list.size() == 2), anyLong());
        verify(queueEngine).executeBulkLua(eq("q_b"), argThat(list -> list.size() == 1), anyLong());
    }

    @Test
    @DisplayName("한 queue의 요청이 CHUNK_SIZE(500)를 넘으면 청크로 나눠 여러 번 Bulk 실행한다")
    void largeQueue_splitsIntoChunks() {
        // given: q_a에 1200건 → 500/500/200 = 3청크
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 1200; i++) {
            global.offer(new PendingEnqueue("q_a", "u" + i));
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(100000)));
        // 청크마다 크기가 다르므로(500/500/200) 요청 크기에 맞춰 결과를 생성한다
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong())).thenAnswer(inv -> {
            List<PendingEnqueue> chunk = inv.getArgument(1);
            return chunk.stream().map(p -> (Object) p.getIdentifier()).toList();
        });
        when(queueEngine.parseBulkResult(anyList())).thenAnswer(inv -> {
            List<Object> raw = inv.getArgument(0);
            List<EnqueueResult> results = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                results.add(EnqueueResult.ok((String) raw.get(i), i, i + 1));
            }
            return results;
        });

        // when
        batchProcessor.processBatches();

        // then: 1200건 → 3청크 (500, 500, 200)
        verify(queueEngine, times(3)).executeBulkLua(eq("q_a"), anyList(), anyLong());
    }

    @Test
    @DisplayName("Bulk 실행 중 예외가 나면 해당 청크의 모든 Future를 예외로 완료한다")
    void bulkFailure_failsAllPendingInChunk() {
        // given
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1");
        global.offer(p1);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong()))
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
        PendingEnqueue p1 = new PendingEnqueue("q_a", "dup");
        PendingEnqueue p2 = new PendingEnqueue("q_a", "dup");
        PendingEnqueue p3 = new PendingEnqueue("q_a", "dup");
        global.offer(p1);
        global.offer(p2);
        global.offer(p3);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> raw = List.of("raw");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong())).thenReturn(raw);
        when(queueEngine.parseBulkResult(raw)).thenReturn(List.of(
                EnqueueResult.ok("dup", 0, 1),
                EnqueueResult.exists("dup", 0, 1),
                EnqueueResult.exists("dup", 0, 1)
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
        PendingEnqueue p1 = new PendingEnqueue("q_a", "u1");
        PendingEnqueue p2 = new PendingEnqueue("q_a", "u2");
        global.offer(p1);
        global.offer(p2);

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_a")).thenReturn(Optional.of(mockQueue(10000)));

        List<Object> raw = List.of("raw");
        when(queueEngine.executeBulkLua(eq("q_a"), anyList(), anyLong())).thenReturn(raw);
        when(queueEngine.parseBulkResult(raw)).thenReturn(List.of(EnqueueResult.ok("u1", 0, 1)));

        // when
        batchProcessor.processBatches();

        // then: 일부만 성공시키지 않고 전원 예외
        assertThat(p1.getFuture()).isCompletedExceptionally();
        assertThat(p2.getFuture()).isCompletedExceptionally();
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
}
