package com.sonix.queue.domain.queue;

/**
 * 이유: 토큰 생명주기 이벤트(ENQUEUED·ADMITTED·COMPLETED·EXPIRED) 발행 포트. 도메인은 메시징 기술을 모른다.
 * 해결: 구현은 {@code KafkaEnqueueEventPublisher}(토픽 {@code token-lifecycle}, 키 {@code tokenId}).
 * 🔴 enqueue 는 OK(신규 발급)만 발행한다 — EXISTS/FULL 을 발행하면 토큰·과금이 중복된다.
 *
 * @author sonix
 */
public interface EnqueueEventPublisher {
    void publish(EnqueueEvent event);

    /**
     * 이유: 여러 건을 <b>한 번에</b> 발행한다 — admit 한 번이 최대 300건을 낸다.
     * 문제: 건별 ack 대기는 지연이 건수에 선형이었다(AWS 12차 300건 p50 1.797초) — 묶어 보내려 기다리는 시간(linger 5ms)을 건마다 낸다.
     * 해결: 전량 보낸 뒤 한 번에 기다린다. 실패 1건이 나머지를 데려가지 않는다(Kafka 에 트랜잭션이 없어 레코드가 서로 독립).
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
