package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 종료 drain의 MAX_DRAIN(5000) 경계 검증.
 *
 * <p><b>왜 HTTP로 재현하지 않는가:</b> 로컬에서 enqueue 유입 속도는 요청당 DB 조회
 * (findByQueueId)에 묶여 초당 1,000~1,600건 수준이다. @Scheduled가 1초마다 최대 5,000건을
 * 비우므로, Global Queue backlog가 5,000을 넘는 상태를 HTTP 부하로 만들 수 없다
 * (실측: 8,000 동시 요청에서도 backlog는 수백 수준). 그래서 "마지막 drain이 한 번뿐이면
 * 5,000건까지만 처리된다"는 가설은 이 레벨에서 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class BatchProcessorShutdownDrainBoundaryTest {

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
    @DisplayName("MAX_DRAIN(5000)을 넘는 잔여가 있어도 종료 drain은 반복 실행되어 전부 완결시킨다")
    void stop_drainsBeyondMaxDrain() {
        int total = 12_000;   // MAX_DRAIN 5000의 2.4배 → 한 번만 돌면 7,000건이 남는다
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        List<PendingEnqueue> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            PendingEnqueue p = new PendingEnqueue("q_test_boundary", "u" + i, "tok_" + i);
            all.add(p);
            global.offer(p);
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_test_boundary")).thenReturn(Optional.of(activeQueue()));
        stubBulkEcho();

        batchProcessor.start();
        batchProcessor.stop();

        assertThat(global).isEmpty();
        // 카운트로 본다. allSatisfy는 실패 건마다 메시지를 모아 하나의 문자열로 조립하는데,
        // 여기 all은 수천~1만 건이라 부분 실패 시 그 문자열이 수백 MB가 되어 Gradle daemon을
        // OOM으로 죽인다(실측 재현). 회귀 탐지선이 회귀 때 못 쓰이면 목적과 모순이다.
        assertThat(all.stream().filter(p -> p.getFuture().isDone()).count()).isEqualTo(all.size());
        assertThat(all.stream().filter(p -> p.getFuture().isCompletedExceptionally()).count()).isZero();
    }

    @Test
    @DisplayName("5초 안에 못 비우면 남은 요청은 매달리지 않고 예외로 완결된다(무응답 아님)")
    void stop_whenDrainTooSlow_failsRemainingInsteadOfHanging() {
        int total = 12_000;
        ConcurrentLinkedQueue<PendingEnqueue> global = new ConcurrentLinkedQueue<>();
        List<PendingEnqueue> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            PendingEnqueue p = new PendingEnqueue("q_test_slow", "u" + i, "tok_" + i);
            all.add(p);
            global.offer(p);
        }

        when(queueEngine.getGlobalQueue()).thenReturn(global);
        when(queueRepository.findByQueueId("q_test_slow")).thenReturn(Optional.of(activeQueue()));
        // 청크(500건)당 400ms → 12,000건이면 9.6s 필요 = SHUTDOWN_DRAIN_TIMEOUT_MS(5s) 초과
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(400);
                    return new ArrayList<>((List<PendingEnqueue>) inv.getArgument(1));
                });
        when(queueEngine.parseBulkResult(anyList())).thenAnswer(BatchProcessorShutdownDrainBoundaryTest::echo);

        batchProcessor.start();
        long t0 = System.nanoTime();
        batchProcessor.stop();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 잔여를 조용히 버리지 않는다: 큐는 비고, 모든 Future가 어떤 형태로든 완결된다
        assertThat(global).isEmpty();
        // isCompleted()는 "정상 완료"만 통과하므로 여기서는 isDone()으로 본다(예외 완결도 완결).
        // allSatisfy가 아니라 카운트인 이유는 위 boundary 테스트의 주석 참조(부분 실패 시 OOM).
        assertThat(all.stream().filter(p -> p.getFuture().isDone()).count()).isEqualTo(all.size());
        long failed = all.stream().filter(p -> p.getFuture().isCompletedExceptionally()).count();
        assertThat(failed).isGreaterThan(0);
        // 상한 = 데드라인 5,000ms + 검사를 통과해 이미 시작된 청크 1회(스텁 400ms) = 5,400ms.
        // 이 값이 회귀 탐지선이다 — 데드라인 검사가 다시 사이클 바깥으로 올라가면
        // 청크 24회(9.6s)를 다 돌아 여기서 깨진다.
        // 실측 5,23x~5,26x(머신마다 20~30ms 편차) / 잡아야 할 회귀 신호 8,04x.
        // 6,000ms는 여유가 743ms(12.4%)뿐이라 CI의 GC·JIT에 흔들린다. 7,000ms면
        // 회귀까지 1,042ms 여유가 남고, 6~7s에 착지하는 회귀는 알려진 경로에 없다.
        // 운영 상한은 스텁 400ms 대신 Redis commandTimeout 5s가 들어가 5s + 5s ≈ 10s
        // (단, MySQL이 응답한다는 전제 — JDBC socketTimeout 미설정. BatchProcessor javadoc 참고).
        assertThat(elapsedMs).isLessThan(7_000L);
        System.out.printf("[boundary] elapsed=%dms, failedFutures=%d/%d%n", elapsedMs, failed, total);
    }

    @SuppressWarnings("unchecked")
    private void stubBulkEcho() {
        when(queueEngine.executeBulkLua(anyString(), anyList(), anyLong(), any(Instant.class)))
                .thenAnswer(inv -> new ArrayList<>((List<PendingEnqueue>) inv.getArgument(1)));
        when(queueEngine.parseBulkResult(anyList()))
                .thenAnswer(BatchProcessorShutdownDrainBoundaryTest::echo);
    }

    @SuppressWarnings("unchecked")
    private static List<EnqueueResult> echo(org.mockito.invocation.InvocationOnMock inv) {
        List<PendingEnqueue> chunk = (List<PendingEnqueue>) inv.getArgument(0);
        List<EnqueueResult> out = new ArrayList<>(chunk.size());
        long seq = 0;
        for (PendingEnqueue p : chunk) {
            out.add(EnqueueResult.ok(p.getIdentifier(), p.getTokenId(), seq, chunk.size(), ++seq,
                    Instant.parse("2026-08-11T00:00:00Z")));
        }
        return out;
    }

    private Queue activeQueue() {
        return Queue.reconstruct(
                1L, "q_test_boundary", 1L, "경계 테스트큐", 2_000_000, 7200, 300,
                com.sonix.queue.domain.queue.QueueStatus.ACTIVE,
                java.time.LocalDateTime.now(), null
        );
    }
}
