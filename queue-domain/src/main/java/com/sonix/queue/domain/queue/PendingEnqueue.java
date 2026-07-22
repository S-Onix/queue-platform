package com.sonix.queue.domain.queue;


import java.util.concurrent.CompletableFuture;

/**
 * Bulk 처리를 위해 대기 중인 Enqueue 요청.
 *
 * 하이브리드 Enqueue 전략에서 사용된다.
 * SlidingWindowCounter가 임계값(1000 req/s)을 초과했다고 판단하면,
 * 즉시 처리 대신 이 객체로 감싸서 BatchQueue에 저장한다.
 *
 * <p><b>Producer-Consumer 흐름:</b>
 * <ul>
 *   <li>Producer (HTTP 요청 Thread): 요청을 PendingEnqueue로 감싸
 *       BatchQueue에 offer, {@code future.get()}으로 결과 대기</li>
 *   <li>Consumer (@Scheduled Thread): BatchQueue에서 poll,
 *       enqueue_bulk.lua 실행 후 각 PendingEnqueue.complete() 호출</li>
 * </ul>
 *
 * <p>CompletableFuture를 통해 Producer와 Consumer가
 * 스레드 안전하게 결과를 전달한다. 각 PendingEnqueue의 future는
 * 독립적이므로, Consumer가 특정 pending.complete()를 호출하면
 * 해당 Producer만 정확히 깨어나 자기 결과를 획득한다.
 *
 * <p><b>Thread-safety:</b> CompletableFuture는 표준 Java의 스레드 안전한
 * 구현체로, 여러 스레드에서 동시 접근해도 안전하다.
 */
public class PendingEnqueue {
    private final String queueId;
    private final String identifier;
    private final CompletableFuture<EnqueueResult> future;

    public PendingEnqueue(String queueId, String identifier){
        this.queueId = queueId;
        this.identifier = identifier;
        this.future = new CompletableFuture<>();
    }

    public String getQueueId() { return this.queueId;}

    public String getIdentifier() {
        return this.identifier;
    }

    public CompletableFuture<EnqueueResult> getFuture() {
        return this.future;
    }

    /**
     * 처리 결과를 설정한다 (Consumer가 호출).
     *
     * <p>대기 중이던 Producer의 {@code future.get()} 호출이
     * 이 결과를 반환하며 깨어난다.
     *
     * @param result Bulk Lua 실행 결과 (해당 identifier의 결과만)
     */
    public void complete(EnqueueResult result) {
        future.complete(result);
    }

    /**
     * 처리 중 예외 발생 시 설정한다 (Consumer가 호출).
     *
     * <p>대기 중이던 Producer의 {@code future.get()} 호출이
     * 이 예외를 throw하며 깨어난다.
     *
     * @param ex 발생한 예외
     */
    public void completeExceptionally(Throwable ex) {
        future.completeExceptionally(ex);
    }
}
