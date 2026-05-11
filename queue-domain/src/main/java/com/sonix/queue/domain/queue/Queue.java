package com.sonix.queue.domain.queue;

import com.sonix.queue.common.util.IdGenerator;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Queue {
    private final int SLICE_STAND_COUNT = 100000;

    Long id;
    String queueId;
    Long tenantId;
    String name;
    int maxCapacity;
    int sliceCount;
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
        this.maxCapacity = maxCapacity;
        this.sliceCount = getSliceStandCount(this.maxCapacity);
        this.waitingTtl = waitingTtl;
        this.inactiveTtl = inactiveTtl;
        this.status = QueueStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    private int getSliceStandCount(int maxCapacity) {
        return (int) Math.ceil((double) maxCapacity / SLICE_STAND_COUNT);
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
        * 현재 큐에 들어있는 갯수와 maxCapacityCount 비교 후 가능 여부 반환 / Queue과 ACTIVE 상태인지 확인
        * */
        return this.status == QueueStatus.ACTIVE;
    }

    public boolean isCapacityExceeded(int currentCount) {
        return currentCount >= maxCapacity;
    }

    public static Queue reconstruct(Long id, String queueId, Long tenantId, String name,
                                    int maxCapacity, int sliceCount,
                                    int waitingTtl, int inactiveTtl,
                                    QueueStatus status, LocalDateTime createdAt,
                                    LocalDateTime deletedAt) {
        Queue queue = new Queue();
        queue.id = id;
        queue.queueId = queueId;
        queue.tenantId = tenantId;
        queue.name = name;
        queue.maxCapacity = maxCapacity;
        queue.sliceCount = sliceCount;
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
