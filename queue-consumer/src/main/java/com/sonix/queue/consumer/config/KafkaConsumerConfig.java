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
 * 소비 실패 처리 정책.
 *
 * <p><b>실패를 두 갈래로 나누는 것이 전부다:</b>
 * <ul>
 *   <li><b>데이터가 잘못됨</b>(제약 위반) — 몇 번을 다시 넣어도 절대 성공하지 않는다.
 *       재시도는 시간 낭비이고, 그 사이 뒤에 있는 정상 항목이 전부 막힌다. 즉시 격리한다.</li>
 *   <li><b>일시적 장애</b>(DB 연결 실패, 타임아웃) — 곧 성공할 수 있다. 격리하면 멀쩡한
 *       수백 건을 버리게 된다. 잠시 쉬었다 다시 시도한다.</li>
 * </ul>
 *
 * <p>이 구분이 없으면 둘 중 하나가 반드시 터진다. 전부 격리하면 DB가 잠깐 죽었을 때 데이터가
 * 통째로 사라지고, 전부 재시도하면 못 고치는 한 건이 그 뒤의 모든 항목을 영원히 막는다
 * (독약 메시지).
 *
 * <p>Spring Kafka의 기본 동작은 <b>전자에 가깝다</b> — 예외 종류를 가리지 않고 재시도한 뒤
 * 격리한다. 그래서 재시도 대상에서 빼는 예외를 명시해야 한다.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * 일시적 장애의 재시도 간격과 횟수.
     *
     * <p>짧게 여러 번보다 <b>넉넉히 몇 번</b>이 낫다. DB 재기동이나 failover는 초 단위로
     * 걸리는데, 100ms 간격으로 10번 시도해봐야 1초 만에 소진되고 격리로 넘어간다.
     *
     * <p>⚠️ 총 대기 시간({@code 간격 × 횟수})이 {@code max.poll.interval.ms}를 넘으면
     * 재시도 도중에 컨슈머가 그룹에서 추방된다. 여유를 두고 잡을 것.
     */
    private static final long RETRY_INTERVAL_MS = 2_000L;
    private static final long RETRY_ATTEMPTS = 3L;

    /**
     * 격리 대상은 {@code <원본토픽>.DLT}로 보낸다 (Spring Kafka 기본 규칙).
     *
     * <p><b>파티션 번호도 원본과 같게 간다.</b> DLT의 파티션 수가 원본보다 적으면 존재하지
     * 않는 파티션으로 보내려다 실패하므로, 두 토픽의 파티션 수를 맞춰야 한다
     * ({@code scripts/kafka/create-topics.sh}가 함께 만든다).
     *
     * <p>DLT는 <b>자동으로 비우지 않는다.</b> 원인을 조사하고 되돌리라고 남겨둔 자리이므로,
     * 길이가 계속 늘어난다면 그 자체가 조사 신호다.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS));

        // 제약 위반은 재시도해도 결과가 같다 → 곧장 DLT.
        // 이 한 줄이 없으면 못 고치는 한 건 때문에 파티션 전체가 멈춘다.
        handler.addNotRetryableExceptions(DataIntegrityViolationException.class);

        // 리스너가 "이 한 건은 지금 처리할 수 없다"고 스스로 판정해 던진 것이다(모르는 이벤트 타입).
        // 판정에 쓰인 근거가 메시지 본문이라 다시 시도해도 답이 같다.
        //
        // 🔴 이 예외는 cause가 없다. 위의 제약 위반과 달리 원인 사슬을 타고 걸릴 것이 없어
        //    명시하지 않으면 기본값인 '재시도'로 분류된다 → 한 건마다 2초 × 3회를 태우고서야 DLT다.
        //    오배포로 미지원 타입이 1만 건 흘러들면 1건당 6초 × 1만 = 16시간이고,
        //    그 사이 같은 파티션 뒤에 줄 선 정상 enqueue가 통째로 멈춘다.
        handler.addNotRetryableExceptions(BatchListenerFailedException.class);

        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }
}
