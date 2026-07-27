package com.sonix.queue.domain.queue;

/**
 * Enqueue 완료 이벤트 발행 포트.
 *
 * <p>구현(Redis List outbox 어댑터)은 queue-infrastructure에 둔다. 도메인은 메시징 기술을 모른다.
 * OK(신규 발급)만 발행하며, EXISTS/FULL은 발행 대상이 아니다(중복 토큰·과금 방지).
 */
public interface EnqueueEventPublisher {
    void publish(EnqueueEvent event);
}
