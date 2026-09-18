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
 * 이유: Kafka 기반 토큰 생명주기 이벤트 발행 어댑터.
 * 🔑 <b>파티션 키가 {@code tokenId} 인 것이 이 클래스의 핵심</b>이다 — 같은 키는 같은 파티션이고 한 파티션은 그룹 안에서 한 소비자가 독점하므로, 한 토큰의 이벤트가 <b>순서대로 한 소비자에게</b> 간다.
 * 원인: {@code queueId} 를 키로 쓰지 않는 것은 분산 때문이다 — "한 큐 30만 명"이 정상 시나리오라
 *       키로 잡으면 파티션을 늘려도 <b>소비자 한 대가 병목</b>이 된다(§73 D18).
 * 🪤 순서 보장은 <b>발행 순서가 옳다는 전제 위에서만</b> 의미가 있다 — 틀린 순서도 그대로 지킨다(§91).
 *
 * @author sonix
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
     * 이유: 이벤트를 발행하고 <b>브로커의 확인을 기다린다</b>.
     * 문제: {@code send()} 는 비동기라 그냥 두면 실패가 조용히 삼켜진다.
     * 원인: 이 시점엔 Lua 가 이미 성공해 순번이 잡혀 있어, 200 을 돌려주면 <b>Redis 엔 있고 DB 엔 영영 없는 유령 토큰</b>이 생긴다 → 503 으로 올린다.
     * 🔑 대기 비용은 작다 — Virtual Thread 라 OS 스레드를 안 잡고, enqueue 는 이미 드레인을 기다린다.
     * 🪤 <b>이것으로 at-least-once 가 완성되지는 않는다</b> — Lua 와 발행은 두 시스템에 대한 두 왕복이라 그 사이에 죽으면 아무 코드도 못 돈다. 이 갭은 <b>대사</b>로 메우는 수밖에 없다.
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
