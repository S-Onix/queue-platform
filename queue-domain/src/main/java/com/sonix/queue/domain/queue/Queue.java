package com.sonix.queue.domain.queue;

import com.sonix.queue.common.util.IdGenerator;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Queue {

    Long id;
    String queueId;
    Long tenantId;
    String name;
    int maxCapacity;
    int waitingTtl;
    int inactiveTtl;
    QueueStatus status;
    LocalDateTime createdAt;
    LocalDateTime deletedAt;

    private Queue() {

    }

    private Queue(Long tenantId, String name, int maxCapacity, int waitingTtl, int inactiveTtl) {
        this.queueId = IdGenerator.generate("q_");
        this.tenantId = tenantId;
        this.name = name;
        this.maxCapacity = maxCapacity;this.waitingTtl = waitingTtl;
        this.inactiveTtl = inactiveTtl;
        this.status = QueueStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public static Queue create(Long tenantId, String name, int maxCapacity, Integer waitingTtl, Integer inactiveTtl) {
        return new Queue(tenantId, name, maxCapacity
                , waitingTtl != null ? waitingTtl : 7200
                , inactiveTtl != null ? inactiveTtl : 300);
    }

    public void update(String name) {
        if (this.status == QueueStatus.DELETED) {
            throw new IllegalStateException("삭제된 대기열은 수정할 수 없습니다");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다");
        }
        this.name = name;

    }


    public boolean isEnqueueable(){
        /*
         * 🔴 여기서 보는 것은 **상태뿐이다.** 정원(maxCapacity)은 enqueue_bulk.lua 가 ZCARD 로 본다(§66 D6).
         * 확인과 삽입이 한 EVAL 안이어야 원자적이다 — 여기서 미리 세면 그 사이 남이 들어와 정원을 넘긴다(TOCTOU).
         */
        return this.status == QueueStatus.ACTIVE;
    }

    /**
     * PAUSED는 <b>입구만 잠근다</b> — 이미 줄에 선 사람의 재진입(새로고침)까지 막지 않는다.
     *
     * <p>{@code DRAINING}을 지우면서 남긴 문장("신규만 막고 기존은 흘린다")이 PAUSED의 정의인데,
     * 코드는 {@link #isEnqueueable()} 하나로 둘을 같이 막고 있었다. 새로고침 한 번에 자리를 잃는다.
     *
     * <p>🔑 <b>여기서 "기존"인지는 판정하지 않는다.</b> 그건 Redis의 중복 게이트가 안다
     * ({@code QueueEngine.hasToken}). 이 메서드는 <b>물어볼 가치가 있는 상태인가</b>만 답한다.
     */
    public boolean allowsRejoin(){
        return this.status == QueueStatus.PAUSED;
    }

    /**
     * 삭제된 큐인가. 소프트 삭제라 행은 남으므로 <b>존재 확인만으로는 걸러지지 않는다</b> —
     * 실제로 지운 큐에서 {@code admit}이 200을 내고 있었다.
     */
    public boolean isDeleted(){
        return this.status == QueueStatus.DELETED;
    }

    public boolean isCapacityExceeded(int currentCount) {
        return currentCount >= maxCapacity;
    }


    public static Queue reconstruct(Long id, String queueId, Long tenantId, String name,
                                    int maxCapacity,
                                    int waitingTtl, int inactiveTtl,
                                    QueueStatus status, LocalDateTime createdAt,
                                    LocalDateTime deletedAt) {
        Queue queue = new Queue();
        queue.id = id;
        queue.queueId = queueId;
        queue.tenantId = tenantId;
        queue.name = name;
        queue.maxCapacity = maxCapacity;
        queue.waitingTtl = waitingTtl;
        queue.inactiveTtl = inactiveTtl;
        queue.status = status;
        queue.createdAt = createdAt;
        queue.deletedAt = deletedAt;
        return queue;
    }

    public void pause(){
        if(this.status != QueueStatus.ACTIVE) {
            throw new IllegalStateException("The queue is not ACTIVE");
        }
        this.status = QueueStatus.PAUSED;
    }

    public void resume(){
        if(this.status != QueueStatus.PAUSED) {
            throw new IllegalStateException("The queue is not PAUSED");
        }
        this.status = QueueStatus.ACTIVE;
    }

    public void delete(){
        if(this.status != QueueStatus.PAUSED) {
            throw new IllegalStateException("The queue is not PAUSED");
        }
        this.status = QueueStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }


}
