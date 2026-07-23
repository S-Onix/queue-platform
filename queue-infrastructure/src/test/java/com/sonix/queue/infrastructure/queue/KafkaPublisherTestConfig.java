package com.sonix.queue.infrastructure.queue;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * KafkaEnqueueEventPublisher 통합 테스트 전용 부트스트랩.
 *
 * <p>Kafka 오토컨피그만 켜서(@ImportAutoConfiguration) 프로덕션과 동일하게 자동 구성된
 * KafkaTemplate이 KafkaEnqueueEventPublisher(생성자 {@code KafkaTemplate<String, Object>})에
 * <b>실제로 주입되는지</b>까지 검증한다. Redis/JPA 오토컨피그는 끌어오지 않는다.
 *
 * <p>@SpringBootConfiguration을 붙이지 않는다(같은 패키지의 다른 부트스트랩과 충돌 방지) —
 * 사용하는 테스트가 {@code @SpringBootTest(classes = ...)}로 명시 지정한다.
 */
@Configuration
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
public class KafkaPublisherTestConfig {

    @Bean
    public KafkaEnqueueEventPublisher kafkaEnqueueEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaEnqueueEventPublisher(kafkaTemplate);
    }
}
