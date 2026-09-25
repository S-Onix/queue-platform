package com.sonix.queue.domain.queue;

/**
 * Enqueue 완료 이벤트 발행 포트.
 *
 * <p>구현(Redis List outbox 어댑터)은 queue-infrastructure에 둔다. 도메인은 메시징 기술을 모른다.
 * OK(신규 발급)만 발행하며, EXISTS/FULL은 발행 대상이 아니다(중복 토큰·과금 방지).
 */
public interface EnqueueEventPublisher {
    void publish(EnqueueEvent event);

    /**
     * 이유: 여러 건을 <b>한 번에</b> 발행한다 — admit 한 번이 최대 300건을 낸다.
     * 문제: 건별 ack 대기는 지연이 건수에 선형이었다(AWS 12차 300건 p50 1.797초) — linger 를 건마다 전액 지불한다.
     * 해결: 전량 보낸 뒤 한 번에 기다린다. 실패 1건이 나머지를 데려가지 않는다(Kafka 에 트랜잭션이 없다, 폭발 반경 N → 1).
     * 🪤 기본 구현은 건별이다(테스트 페이크용) — <b>운영 어댑터는 반드시 재정의한다</b>. 실패분은 복구되지 않는다. §96-7
     *
     * @author sonix
     * @return 발행에 실패한 건수 (0 = 전부 성공)
     */
    default int publishAll(java.util.List<EnqueueEvent> events) {
        int failed = 0;
        for (EnqueueEvent event : events) {
            try {
                publish(event);
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return failed;
    }
}
