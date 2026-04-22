package com.sonix.queue.domain.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class QueueTest {
    @Test
    @DisplayName("생성 시 queueId는 q_로 시작")
    void create_queueId_starts_with_q() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertTrue(queue.getQueueId().startsWith("q_"));
    }

    @Test
    @DisplayName("생성 시 status는 ACTIVE")
    void create_status_is_active() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertEquals(QueueStatus.ACTIVE, queue.getStatus());
    }

    @Test
    @DisplayName("생성 시 waitingTtl 기본값 7200")
    void create_default_waitingTtl() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertEquals(7200, queue.getWaitingTtl());
    }

    @Test
    @DisplayName("생성 시 inactiveTtl 기본값 300")
    void create_default_inactiveTtl() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertEquals(300, queue.getInactiveTtl());
    }

    @Test
    @DisplayName("생성 시 커스텀 TTL 적용")
    void create_custom_ttl() {
        Queue queue = Queue.create(1L, "test-queue", 100000, 3600, 600);
        assertEquals(3600, queue.getWaitingTtl());
        assertEquals(600, queue.getInactiveTtl());
    }

    @Test
    @DisplayName("sliceCount 자동 계산 - 250000 → 3")
    void create_sliceCount_250000() {
        Queue queue = Queue.create(1L, "test-queue", 250000, null, null);
        assertEquals(3, queue.getSliceCount());
    }

    @Test
    @DisplayName("sliceCount 자동 계산 - 50000 → 1")
    void create_sliceCount_50000() {
        Queue queue = Queue.create(1L, "test-queue", 50000, null, null);
        assertEquals(1, queue.getSliceCount());
    }

    @Test
    @DisplayName("sliceCount 자동 계산 - 100000 → 1")
    void create_sliceCount_100000() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertEquals(1, queue.getSliceCount());
    }

    @Test
    @DisplayName("pause 성공")
    void pause_success() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.pause();
        assertEquals(QueueStatus.PAUSED, queue.getStatus());
    }

    @Test
    @DisplayName("pause - PAUSED 상태에서 예외")
    void pause_already_paused_throws() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.pause();
        assertThrows(IllegalStateException.class, () -> queue.pause());
    }

    @Test
    @DisplayName("resume 성공")
    void resume_success() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.pause();
        queue.resume();
        assertEquals(QueueStatus.ACTIVE, queue.getStatus());
    }

    @Test
    @DisplayName("resume - ACTIVE 상태에서 예외")
    void resume_already_active_throws() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertThrows(IllegalStateException.class, () -> queue.resume());
    }

    @Test
    @DisplayName("drain 성공")
    void drain_success() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        assertEquals(QueueStatus.DRAINING, queue.getStatus());
    }

    @Test
    @DisplayName("delete - DRAINING에서 성공")
    void delete_from_draining() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        queue.delete();
        assertEquals(QueueStatus.DELETED, queue.getStatus());
        assertNotNull(queue.getDeletedAt());
    }

    @Test
    @DisplayName("delete - PAUSED에서 성공")
    void delete_from_paused() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.pause();
        queue.delete();
        assertEquals(QueueStatus.DELETED, queue.getStatus());
        assertNotNull(queue.getDeletedAt());
    }

    @Test
    @DisplayName("delete - ACTIVE에서 예외")
    void delete_from_active_throws() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertThrows(IllegalStateException.class, () -> queue.delete());
    }

    @Test
    @DisplayName("isEnqueueable - ACTIVE면 true")
    void isEnqueueable_active() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertTrue(queue.isEnqueueable());
    }

    @Test
    @DisplayName("isEnqueueable - PAUSED면 false")
    void isEnqueueable_paused() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.pause();
        assertFalse(queue.isEnqueueable());
    }

    @Test
    @DisplayName("isCapacityExceeded - 같으면 true")
    void isCapacityExceeded_equal() {
        Queue queue = Queue.create(1L, "test-queue", 100, null, null);
        assertTrue(queue.isCapacityExceeded(100));
    }

    @Test
    @DisplayName("isCapacityExceeded - 적으면 false")
    void isCapacityExceeded_less() {
        Queue queue = Queue.create(1L, "test-queue", 100, null, null);
        assertFalse(queue.isCapacityExceeded(99));
    }

    @Test
    @DisplayName("큐 전체 라이프사이클: ACTIVE → PAUSED → ACTIVE → DRAINING → DELETED")
    void full_lifecycle() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        assertEquals(QueueStatus.ACTIVE, queue.getStatus());

        queue.pause();
        assertEquals(QueueStatus.PAUSED, queue.getStatus());

        queue.resume();
        assertEquals(QueueStatus.ACTIVE, queue.getStatus());

        queue.drain();
        assertEquals(QueueStatus.DRAINING, queue.getStatus());

        queue.delete();
        assertEquals(QueueStatus.DELETED, queue.getStatus());
        assertNotNull(queue.getDeletedAt());
    }

    @Test
    @DisplayName("DRAINING에서 pause 불가")
    void draining_cannot_pause() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        assertThrows(IllegalStateException.class, () -> queue.pause());
    }

    @Test
    @DisplayName("DRAINING에서 resume 불가")
    void draining_cannot_resume() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        assertThrows(IllegalStateException.class, () -> queue.resume());
    }

    @Test
    @DisplayName("DRAINING에서 drain 중복 불가")
    void draining_cannot_drain_again() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        assertThrows(IllegalStateException.class, () -> queue.drain());
    }

    @Test
    @DisplayName("DELETED에서 모든 전환 불가")
    void deleted_cannot_do_anything() {
        Queue queue = Queue.create(1L, "test-queue", 100000, null, null);
        queue.drain();
        queue.delete();

        assertThrows(IllegalStateException.class, () -> queue.pause());
        assertThrows(IllegalStateException.class, () -> queue.resume());
        assertThrows(IllegalStateException.class, () -> queue.drain());
        assertThrows(IllegalStateException.class, () -> queue.delete());
    }

    @Test
    @DisplayName("sliceCount - 100001 → 2 (경계 바로 넘김)")
    void sliceCount_boundary_100001() {
        Queue queue = Queue.create(1L, "test", 100001, null, null);
        assertEquals(2, queue.getSliceCount());
    }

    @Test
    @DisplayName("sliceCount - 1 → 1 (최소값)")
    void sliceCount_minimum() {
        Queue queue = Queue.create(1L, "test", 1, null, null);
        assertEquals(1, queue.getSliceCount());
    }

    @Test
    @DisplayName("sliceCount - 1000000 → 10 (대규모)")
    void sliceCount_large_scale() {
        Queue queue = Queue.create(1L, "test", 1000000, null, null);
        assertEquals(10, queue.getSliceCount());
    }

    @Test
    @DisplayName("isCapacityExceeded - 초과하면 true")
    void isCapacityExceeded_over() {
        Queue queue = Queue.create(1L, "test", 100, null, null);
        assertTrue(queue.isCapacityExceeded(101));
    }

    @Test
    @DisplayName("isCapacityExceeded - 0명이면 false")
    void isCapacityExceeded_zero() {
        Queue queue = Queue.create(1L, "test", 100, null, null);
        assertFalse(queue.isCapacityExceeded(0));
    }

    @Test
    @DisplayName("isEnqueueable - DRAINING이면 false")
    void isEnqueueable_draining() {
        Queue queue = Queue.create(1L, "test", 100000, null, null);
        queue.drain();
        assertFalse(queue.isEnqueueable());
    }

    @Test
    @DisplayName("isEnqueueable - DELETED면 false")
    void isEnqueueable_deleted() {
        Queue queue = Queue.create(1L, "test", 100000, null, null);
        queue.drain();
        queue.delete();
        assertFalse(queue.isEnqueueable());
    }

    @Test
    @DisplayName("reconstruct - 모든 필드 정확히 복원")
    void reconstruct_all_fields() {
        LocalDateTime now = LocalDateTime.now();
        Queue queue = Queue.reconstruct(
                1L, "q_test123", 100L, "my-queue",
                200000, 2, 3600, 600,
                QueueStatus.PAUSED, now, null
        );

        assertEquals(1L, queue.getId());
        assertEquals("q_test123", queue.getQueueId());
        assertEquals(100L, queue.getTenantId());
        assertEquals("my-queue", queue.getName());
        assertEquals(200000, queue.getMaxCapacity());
        assertEquals(2, queue.getSliceCount());
        assertEquals(3600, queue.getWaitingTtl());
        assertEquals(600, queue.getInactiveTtl());
        assertEquals(QueueStatus.PAUSED, queue.getStatus());
        assertEquals(now, queue.getCreatedAt());
        assertNull(queue.getDeletedAt());
    }
}
