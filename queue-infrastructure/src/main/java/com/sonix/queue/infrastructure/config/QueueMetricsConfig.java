package com.sonix.queue.infrastructure.config;

import com.sonix.queue.infrastructure.queue.RedisQueueEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * 이유: enqueue 대기 큐(globalQueue)의 깊이를 밖에서 볼 수 있게 한다.
 * 문제: 그 큐는 상한 없는 {@code ConcurrentLinkedQueue} 인데 **크기를 재는 수단이 0** 이었다.
 * 원인: 드레인이 멈추면 생산만 계속돼 힙이 자라는데, 그 상태를 감지할 지표가 없었다.
 * 해결: Gauge 하나. 상한을 만들기 전에 **"쌓이는 중"을 먼저 보이게** 한다(§4).
 * 🪤 MeterRegistry 가 없는 컨텍스트(단위 테스트)에서도 안전하도록 ObjectProvider 로 받는다.
 *
 * @author sonix
 */
@Configuration
public class QueueMetricsConfig {

    public static final String PENDING_SIZE = "queue.pending.size";

    public QueueMetricsConfig(RedisQueueEngine queueEngine, ObjectProvider<MeterRegistry> registries) {
        registries.ifAvailable(registry ->
                Gauge.builder(PENDING_SIZE, queueEngine, e -> e.getGlobalQueue().size())
                        .description("드레인 대기 중인 enqueue 수. 0에서 멀어지면 배출이 유입을 못 따라간다")
                        .strongReference(true)
                        .register(registry));
    }
}
