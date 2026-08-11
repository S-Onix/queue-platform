package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발행 어댑터가 Boot의 Kafka 자동설정과 맞물리는지 검증한다.
 *
 * <p><b>왜 필요한가:</b> {@link KafkaAutoConfiguration}은 템플릿을
 * {@code KafkaTemplate<?, ?>}로 선언하는데 어댑터는 {@code KafkaTemplate<String, Object>}로
 * 받는다. 제네릭이 맞물리지 않으면 <b>기동 시점에야</b> 주입 실패로 드러나므로 여기서 고정한다.
 *
 * <p>{@link ApplicationContextRunner}를 쓰는 이유는 실제 브로커 없이 <b>빈 구성만</b>
 * 확인하기 위해서다. 배선은 연결 가능 여부와 무관하다.
 */
class EnqueueEventPublisherWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaEnqueueEventPublisher.class);

    @Test
    @DisplayName("Kafka 발행 어댑터가 유일한 EnqueueEventPublisher로 주입된다")
    void kafkaPublisherIsTheSolePublisher() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EnqueueEventPublisher.class);
            assertThat(context.getBean(EnqueueEventPublisher.class))
                    .isInstanceOf(KafkaEnqueueEventPublisher.class);
        });
    }

    @Test
    @DisplayName("토픽·타임아웃 기본값이 설정 없이도 적용된다")
    void defaultsApplyWithoutExplicitProperties() {
        // @Value 기본값이 빠지면 프로퍼티 미설정 환경에서 기동이 깨진다.
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }
}
