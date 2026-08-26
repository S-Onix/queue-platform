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
         * 🔴 여기서 보는 것은 **상태뿐이다.** 정원(maxCapacity) 판정은 하지 않는다.
         *
         * 용량은 enqueue_bulk.lua 가 ZCARD 로 본다(§66 D6) — 그래야 확인과 삽입이 한 EVAL 안에서
         * 원자적이다. 여기서 미리 세면 그 사이에 남이 들어와 정원을 넘긴다(TOCTOU).
         *
         * 구 주석은 "현재 인원과 maxCapacityCount 비교 후 반환"이라고 적혀 있었다 —
         * 그걸 믿고 여기에 용량 검사를 넣으면 Lua 판정을 중복 구현하게 된다.
         */
        return this.status == QueueStatus.ACTIVE;
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

    public void drain(){
        if(this.status != QueueStatus.ACTIVE) {
            throw new IllegalStateException("The queue is not ACTIVE");
        }
        this.status = QueueStatus.DRAINING;
    }

    public void delete(){
        if(this.status != QueueStatus.PAUSED) {
            throw new IllegalStateException("The queue is not PAUSED");
        }
        this.status = QueueStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }


}
