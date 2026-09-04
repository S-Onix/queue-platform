package com.sonix.queue.api.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@code queue_admission_wait_seconds} 단위 테스트 (Mockito, 인프라 없음 → {@code @Tag} 없음).
 *
 * <p>알람 규칙이 <b>이름과 라벨을 문자열로</b> 참조하므로(MONITORING_DESIGN 4-3) 계약을 그대로 건다:
 * 미터 이름 {@code queue.admission.wait} · 라벨 {@code queue_id} · SLO 버킷 8개.
 * 이름이나 라벨이 바뀌면 여기가 빨개진다 — 알람은 조용히 영원히 안 뜬다.
 */
@ExtendWith(MockitoExtension.class)
class AdmissionWaitMetricTest {

    private static final long TENANT_ID = 1L;
    private static final String QUEUE_ID = "q_dev_metric";
    /** admit 시각. issuedAt을 여기서 빼서 대기 시간을 만든다. */
    private static final long NOW = 1_700_000_000_000L;

    @Mock private QueueRepository queueRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private QueueEngine queueEngine;
    @Mock private EnqueueEventPublisher eventPublisher;

    private SimpleMeterRegistry registry;
    private QueueEngineService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), registry);
        when(queueRepository.findByQueueId(QUEUE_ID))
                .thenReturn(Optional.of(Queue.create(TENANT_ID, "메트릭 큐", 100_000, null, null)));
    }

    private void givenAdmit(boolean replay, AdmitResult.AdmitRecord... records) {
        when(queueEngine.admit(anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(new AdmitResult(replay, List.of(records)));
    }

    /** 대기 {@code waitSeconds}초짜리 한 명. 음수를 넣으면 미래에 줄 선 사람(=시계 스큐)이 된다. */
    private AdmitResult.AdmitRecord record(String tokenId, long waitSeconds) {
        return new AdmitResult.AdmitRecord("user-" + tokenId, tokenId, 1L, "adm-" + tokenId,
                Instant.ofEpochMilli(NOW - waitSeconds * 1000));
    }

    private Timer waitTimer() {
        return registry.find("queue.admission.wait").tag("queue_id", QUEUE_ID).timer();
    }

    @Test
    @DisplayName("admit 성공 시 issuedAt→admittedAt 차이를 queue_id 라벨로 기록한다")
    void recordsWait() {
        givenAdmit(false, record("t1", 30), record("t2", 90));

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        Timer timer = waitTimer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(120.0);
        assertThat(timer.max(TimeUnit.SECONDS)).isEqualTo(90.0);
    }

    @Test
    @DisplayName("SLO 버킷 8개가 그대로 노출된다 (알람이 le=60·300을 본다)")
    void publishesSloBuckets() {
        givenAdmit(false, record("t1", 30));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        double[] boundaries = Arrays.stream(waitTimer().takeSnapshot().histogramCounts())
                .mapToDouble(CountAtBucket::bucket)
                .toArray();
        assertThat(boundaries).containsExactly(
                1e10, 3e10, 6e10, 1.2e11, 3e11, 6e11, 1.8e12, 3.6e12);  // 나노초 = 10·30·60·120·300·600·1800·3600초
    }

    @Test
    @DisplayName("Prometheus 노출 이름이 계약과 같다 — queue_admission_wait_seconds_{bucket,count,sum}")
    void prometheusExposition() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        service = new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), prometheus);
        givenAdmit(false, record("t1", 45));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        String scrape = prometheus.scrape();
        assertThat(scrape)
                .contains("queue_admission_wait_seconds_bucket{queue_id=\"" + QUEUE_ID + "\",le=\"60.0\"}")
                .contains("queue_admission_wait_seconds_bucket{queue_id=\"" + QUEUE_ID + "\",le=\"300.0\"}")
                .contains("queue_admission_wait_seconds_count{queue_id=\"" + QUEUE_ID + "\"}")
                .contains("queue_admission_wait_seconds_sum{queue_id=\"" + QUEUE_ID + "\"}");
    }

    @Test
    @DisplayName("음수(시계 스큐)는 Timer 표본에서 빼고 별도 카운터로 드러낸다 — 0으로 눕히지 않는다")
    void skewGoesToCounterNotClampedToZero() {
        givenAdmit(false, record("skewed", -398), record("normal", 10));

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        Timer timer = waitTimer();
        assertThat(timer.count()).isEqualTo(1);                          // 스큐 건은 표본에 없다
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(10.0);   // 0으로 눕혔다면 여기가 10 그대로여도 count가 2다
        assertThat(registry.get("queue.admission.clock.skew").tag("queue_id", QUEUE_ID).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("REPLAY는 기록하지 않는다 — 같은 사람이 두 번 세어지고 대기 시간도 재시도 시각으로 부푼다")
    void replayNotRecorded() {
        givenAdmit(true, record("t1", 30));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        assertThat(waitTimer()).isNull();
    }

    @Test
    @DisplayName("issuedAt이 null인 구 포맷은 건너뛴다 — 발행 생략과 같은 판단")
    void nullIssuedAtSkipped() {
        givenAdmit(false,
                new AdmitResult.AdmitRecord("u", "t-old", 1L, "adm", null),
                record("t2", 20));

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        assertThat(waitTimer().count()).isEqualTo(1);
    }
}
