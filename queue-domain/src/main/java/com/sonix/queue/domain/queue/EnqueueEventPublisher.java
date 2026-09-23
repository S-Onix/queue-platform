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
     * 문제: {@link #publish} 를 루프로 부르면 건당 ack 을 기다려 <b>지연이 건수에 선형</b>이다
     *       (AWS 12차 실측: 300건에 p50 <b>1.797초</b>, 건당 5.99ms).
     * 원인: 건별 ack 대기는 자기 레코드끼리 같은 배치에 못 묶이게 만들어 배칭 대기(linger)를
     *       <b>건마다 전액</b> 지불한다.
     * 해결: 전량 보낸 뒤 <b>한 번에</b> 기다린다. 실패 1건이 나머지를 데려가지 않는다 —
     *       Kafka 에 트랜잭션이 없어 레코드는 서로 독립이다(폭발 반경 N → 1).
     * ⚠️ 실패한 건은 <b>복구되지 않는다</b>. 호출자가 그 수를 세어 관측에 남겨야 한다.
     *
     * 🪤 기본 구현은 <b>건별 발행</b>이다 — 테스트 페이크가 람다 하나로 남을 수 있게 둔 것이고
     *    (이 포트를 함수형으로 쓰는 곳이 있다), <b>운영 어댑터는 반드시 재정의한다</b>.
     *    기본 구현도 첫 실패에서 끊지 않는다 — 폭발 반경 성질은 여기서도 같다.
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
