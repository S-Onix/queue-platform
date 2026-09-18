package com.sonix.queue.domain.queue;


import java.util.concurrent.CompletableFuture;

/**
 * 이유: Bulk 처리를 기다리는 Enqueue 요청 한 건. 모든 요청이 Global Queue 를 거친다(§70).
 * 원인: Producer(HTTP 스레드)가 offer 하고 {@code future.get()} 으로 기다리면,
 *       Consumer(드레인 스레드)가 poll 해 {@code enqueue_bulk.lua} 를 돌리고 complete() 한다.
 * 해결: {@link java.util.concurrent.CompletableFuture} 로 결과를 건넨다 — future 가 건마다 독립이라
 *       해당 Producer 만 깨어난다. 후보 tokenId 도 함께 실어 보낸다(OK 면 Lua 가 채택).
 *
 * @author sonix
 */
public class PendingEnqueue {
    private final String queueId;
    private final String identifier;
    private final String tokenId;
    private final CompletableFuture<EnqueueResult> future;
    /** 큐에 담긴 시각. 드레인 틱까지 얼마나 기다렸는지를 재는 데만 쓴다(queue.stage.duration{stage=tick}). */
    private final long createdNanos;

    public PendingEnqueue(String queueId, String identifier, String tokenId){
        this.queueId = queueId;
        this.identifier = identifier;
        this.tokenId = tokenId;
        this.future = new CompletableFuture<>();
            this.createdNanos = System.nanoTime();
    }

    public String getTokenId() { return this.tokenId;}

    public String getQueueId() { return this.queueId;}

    public String getIdentifier() {
        return this.identifier;
    }

    public long getCreatedNanos() { return this.createdNanos; }

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
