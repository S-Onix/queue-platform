package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 기반 토큰 생명주기 이벤트 발행 어댑터.
 *
 * <p><b>파티션 키가 {@code tokenId}인 것이 이 클래스의 핵심</b>이다. Kafka는 같은 키를
 * 항상 같은 파티션에 넣고({@code murmur2(key) % partitions}), 한 파티션은 컨슈머 그룹 안에서
 * 정확히 한 소비자가 독점한다. 그래서 한 토큰의 이벤트들은 <b>순서대로, 한 소비자에게</b>
 * 도착한다 — {@code WAITING → ADMIT_ISSUED} 같은 상태 전이가 뒤집히지 않는 근거다.
 *
 * <p>{@code queueId}를 키로 쓰지 않는 이유는 분산 때문이다. 이 서비스는 "한 큐에 30만 명"이
 * 정상 시나리오라, 큐를 키로 잡으면 그 트래픽이 통째로 한 파티션에 몰려 파티션을 아무리
 * 늘려도 소비자 한 대가 병목이 된다. {@code tokenId}는 토큰마다 유일해서 모든 파티션에
 * 고루 퍼지면서도, 같은 토큰의 이벤트끼리는 묶인다.
 *
 * <p><b>⚠️ 파티션 수를 늘리면 키→파티션 매핑이 바뀐다.</b> 그 시점에 아직 살아 있는 토큰은
 * 과거 이벤트와 신규 이벤트가 다른 파티션에 놓여 순서 관계가 끊긴다. 토큰 수명이
 * {@code waitingTtl}(2시간)까지 갈 수 있으므로 증설은 조용한 시간대에, 그마저도 신중히.
 */
@Slf4j
@Component
public class KafkaEnqueueEventPublisher implements EnqueueEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaEnqueueEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${queue.event.topic:token-lifecycle}") String topic,
            @Value("${queue.event.send-timeout-ms:3000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
        log.info("enqueue 이벤트 발행: kafka topic={} timeout={}ms", topic, sendTimeoutMs);
    }

    /**
     * 이벤트를 발행하고 브로커의 확인을 기다린다.
     *
     * <p><b>왜 기다리는가:</b> {@code send()}는 비동기라 그냥 두면 실패가 조용히 삼켜진다.
     * 이 시점엔 Lua가 이미 성공해 대기열에 순번이 잡혀 있으므로, 발행이 실패했는데 200을
     * 돌려주면 "Redis 대기열엔 있는데 DB엔 영영 없는" 유령 토큰이 생긴다. 503으로 올려보내야
     * 최소한 호출자가 실패를 인지한다.
     *
     * <p>대기 비용은 크지 않다. 운영 코드가 Virtual Thread 전제라 블로킹이 OS 스레드를 잡지
     * 않고, {@code acks=all}이라도 배치·linger 덕에 수 ms 수준이다. 애초에 enqueue 경로는
     * Redis 배치 처리를 기다리느라 이미 더 오래 블록된다.
     *
     * <p><b>다만 이것으로 at-least-once가 완성되지는 않는다.</b> Lua 실행과 이 발행은 서로 다른
     * 시스템에 대한 두 번의 왕복이고, 그 사이에 프로세스가 죽으면 아무 코드도 실행되지 못한다.
     * Redis Stream 시절에는 {@code XADD}를 Lua 안으로 옮겨 원자화하는 길이 있었지만,
     * Kafka에는 그 길이 없다(Redis와 Kafka 사이에 분산 트랜잭션이 없으므로).
     * 이 갭은 정합성 대사(reconciliation)로 메우는 수밖에 없다.
     */
    @Override
    public void publish(EnqueueEvent event) {
        try {
            // 키를 tokenId로 고정한다. 이 한 줄이 상태 전이 순서 보장의 전제다.
            kafkaTemplate.send(topic, event.tokenId(), event)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            // 인터럽트 상태를 복원하지 않으면 상위 스레드가 취소 신호를 영영 못 본다.
            Thread.currentThread().interrupt();
            log.error("enqueue 이벤트 발행 중 인터럽트 tokenId={} queueId={}",
                    event.tokenId(), event.queueId(), e);
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);

        } catch (ExecutionException | TimeoutException e) {
            // 타임아웃은 "실패"가 아니라 "모름"이다. 브로커가 이미 받았을 수도 있으므로
            // 여기서 재시도를 넣으면 중복이 늘 뿐이다. 중복은 멱등 적재가 흡수하지만,
            // 유실은 아무도 복구해주지 않는다 — 그래서 호출자에게 알리는 것으로 끝낸다.
            log.error("enqueue 이벤트 발행 실패 tokenId={} queueId={}: {}",
                    event.tokenId(), event.queueId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        }
    }
}
