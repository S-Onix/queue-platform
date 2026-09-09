package com.sonix.queue.api.queue;

import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenRepository;
import io.micrometer.core.instrument.Counter;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@code queue_admit_requests_total} / {@code queue_admit_tokens_issued_total} 단위 테스트
 * (Mockito, 인프라 없음 → {@code @Tag} 없음).
 *
 * <p>알람 규칙이 <b>이름과 라벨을 문자열로</b> 참조하므로(alerts/app.yml) 계약을 그대로 건다:
 * 라벨은 {@code queueId}가 아니라 <b>{@code queue_id}</b>여야 {@code and on(queue_id)} 조인이
 * 성립한다. 철자가 갈리면 알람은 빨개지는 대신 <b>영원히 침묵</b>한다 — 그래서 여기가 대신 빨개진다.
 */
@ExtendWith(MockitoExtension.class)
class AdmitRequestMetricTest {

    private static final long TENANT_ID = 1L;
    private static final String QUEUE_ID = "q_dev_metric";
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

    private AdmitResult.AdmitRecord record(String tokenId) {
        return new AdmitResult.AdmitRecord("user-" + tokenId, tokenId, 1L, "adm-" + tokenId,
                Instant.ofEpochMilli(NOW - 30_000));
    }

    /** 롤링 배포 중의 구 포맷 REPLAY — issuedAt이 없어 발행할 수 없는 건. */
    private AdmitResult.AdmitRecord legacyRecord(String tokenId) {
        return new AdmitResult.AdmitRecord("user-" + tokenId, tokenId, 1L, "adm-" + tokenId, null);
    }

    private double requests(String result) {
        Counter counter = registry.find("queue.admit.requests")
                .tag("queue_id", QUEUE_ID).tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double tokensIssued() {
        Counter counter = registry.find("queue.admit.tokens.issued").tag("queue_id", QUEUE_ID).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("admit 성공 = result:ok 1건 + 발급 토큰 수만큼 tokens_issued")
    void countsOk() {
        givenAdmit(false, record("t1"), record("t2"));

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        assertThat(requests("ok")).isEqualTo(1.0);
        assertThat(tokensIssued()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("대기열이 비어 0건이면 result:empty — 요청은 왔다는 것이 남아야 한다")
    void countsEmpty() {
        givenAdmit(false);

        service.admit(TENANT_ID, QUEUE_ID, 5, "req-1");

        assertThat(requests("empty")).isEqualTo(1.0);
        assertThat(requests("ok")).isZero();
        assertThat(tokensIssued()).isZero();
    }

    @Test
    @DisplayName("REPLAY는 요청만 세고 토큰은 세지 않는다 — 새로 발급한 것이 없다")
    void replayDoesNotCountTokens() {
        givenAdmit(true, record("t1"));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        assertThat(requests("replay")).isEqualTo(1.0);
        assertThat(requests("ok")).isZero();
        assertThat(tokensIssued()).isZero();
    }

    @Test
    @DisplayName("ADMITTED 발행이 실패하면 result:error — admit은 200이라 HTTP로는 안 보인다")
    void countsPublishFailureAsError() {
        givenAdmit(false, record("t1"), record("t2"));
        doThrow(new IllegalStateException("broker down")).when(eventPublisher).publish(any());

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        assertThat(requests("error")).isEqualTo(1.0);
        assertThat(requests("ok")).isZero();
    }

    @Test
    @DisplayName("issuedAt이 없어 발행을 건너뛴 건도 result:error — complete가 영구 404가 되는 건 같다")
    void countsSkippedLegacyRecordAsError() {
        givenAdmit(false, legacyRecord("t1"));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        assertThat(requests("error")).isEqualTo(1.0);
    }

    /**
     * 🔴 REPLAY + 구 포맷은 {@code error}가 아니다. 이 조합이 null issuedAt의 <b>유일한 실제
     * 경로</b>인데(RedisQueueEngine.parseAdmitResult javadoc), error로 접히면 critical 알람이
     * 뜨고 런북이 <b>이미 정상인 행</b>을 손으로 고치라고 시킨다 — 첫 호출에서 이미 발행됐다.
     */
    @Test
    @DisplayName("REPLAY의 구 포맷 레코드는 error가 아니라 replay — 첫 호출에서 이미 발행됐다")
    void replayWinsOverSkipped() {
        givenAdmit(true, legacyRecord("t1"));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        assertThat(requests("replay")).isEqualTo(1.0);
        assertThat(requests("error")).isZero();
    }

    @Test
    @DisplayName("리스트 중간에서 발행이 끊겨도 요청은 1건 — 남은 전부가 건너뛴 것으로 접힌다")
    void countsPartialFailureAsSingleErrorRequest() {
        givenAdmit(false, record("t1"), record("t2"), record("t3"));
        doThrow(new IllegalStateException("broker down")).when(eventPublisher)
                .publish(argThat(e -> "t2".equals(e.tokenId())));

        service.admit(TENANT_ID, QUEUE_ID, 3, "req-1");

        assertThat(requests("error")).isEqualTo(1.0);
        // 🪤 $value(요청 건수)와 영향 사용자 수는 다르다 — 여기서 1건 대 3명이다.
        //    알람 summary가 "영향 사용자는 그 이상"이라 쓰는 근거가 이것이다.
        assertThat(tokensIssued()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("Prometheus 노출 이름·라벨이 알람 규칙과 같다 — queue_admit_requests_total{queue_id,result}")
    void prometheusExposition() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        service = new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), prometheus);
        givenAdmit(false, record("t1"));

        service.admit(TENANT_ID, QUEUE_ID, 1, "req-1");

        String scrape = prometheus.scrape();
        assertThat(scrape).contains("queue_admit_requests_total{queue_id=\"" + QUEUE_ID + "\",result=\"ok\"}");
        assertThat(scrape).contains("queue_admit_tokens_issued_total{queue_id=\"" + QUEUE_ID + "\"}");
    }
}
