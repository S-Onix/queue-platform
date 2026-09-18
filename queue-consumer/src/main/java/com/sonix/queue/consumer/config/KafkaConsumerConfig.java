package com.sonix.queue.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 이유: 소비 실패를 두 갈래로 나눈다 — 데이터가 잘못됨 vs 일시적 장애.
 * 문제: 전부 격리하면 DB 가 잠깐 죽었을 때 데이터가 통째로 사라지고,
 *       전부 재시도하면 못 고치는 한 건이 뒤의 모든 항목을 영원히 막는다(독약 메시지).
 * 원인: Spring Kafka 기본값은 예외 종류를 가리지 않고 재시도한 뒤 격리한다.
 * 해결: 제약 위반은 즉시 격리, 나머지는 잠시 쉬었다 재시도한다.
 *
 * @author sonix
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * 이유: 일시적 장애의 재시도 간격과 횟수.
     * 문제: 짧게 여러 번이면 DB 재기동·failover(초 단위) 전에 소진돼 격리로 넘어간다.
     * 해결: 넉넉히 몇 번으로 잡는다.
     * ⚠️ 총 대기(간격 × 횟수)가 {@code max.poll.interval.ms} 를 넘으면 그룹에서 추방된다.
     *
     * @author sonix
     */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    private static final long RETRY_ATTEMPTS = 3L;

    /**
     * 이유: 격리 대상을 {@code <원본토픽>.DLT} 로 보낸다(Spring Kafka 기본 규칙).
     * 문제: DLT 의 파티션 수가 원본보다 적으면 없는 파티션으로 보내려다 실패한다.
     * 해결: 파티션 번호를 원본과 같게 가고, 두 토픽을 함께 만든다(scripts/kafka/create-topics.sh).
     * 🪤 DLT 는 자동으로 비우지 않는다 — 길이가 계속 늘면 그 자체가 조사 신호다.
     *
     * @author sonix
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // 제약 위반은 재시도해도 결과가 같다 → 곧장 DLT.
        // 이 한 줄이 없으면 못 고치는 한 건 때문에 파티션 전체가 멈춘다.
        handler.addNotRetryableExceptions(DataIntegrityViolationException.class);

        // 이유: 리스너가 "이 한 건은 지금 처리 못 한다"고 스스로 판정해 던진 것(모르는 타입).
        // 문제: 이 예외는 cause 가 없어 명시 안 하면 기본값 '재시도'로 분류된다.
        // 원인: 오배포로 미지원 타입 1만 건이면 1건당 6초 × 1만 = 16시간이고,
        //       그 사이 같은 파티션 뒤의 정상 enqueue 가 통째로 멈춘다.
        // 해결: 재시도 제외 목록에 명시한다.
        handler.addNotRetryableExceptions(BatchListenerFailedException.class);

        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }
}
