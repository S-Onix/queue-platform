package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KafkaEnqueueEventPublisher implements EnqueueEventPublisher {

    private static final String TOPIC  = "enqueue-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEnqueueEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(EnqueueEvent event) {
        kafkaTemplate.send(TOPIC, event.queueId(), event).whenComplete((result, ex) -> {
            if(ex != null) {
                log.error("enqueue-event 발행 실패 tokenId={} queueId={}: {}",
                        event.tokenId(), event.queueId(), ex.getMessage(), ex);
            }
        });
    }
}
