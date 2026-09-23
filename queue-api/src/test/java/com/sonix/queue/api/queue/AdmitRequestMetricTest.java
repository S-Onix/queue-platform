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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(eventPublisher.publishAll(anyList())).thenReturn(2);   // 둘 다 실패

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        assertThat(requests("error")).isEqualTo(1.0);
        assertThat(requests("ok")).isZero();
    }

    /**
     * 🔴 <b>발행 목록의 내용</b>을 단정한다. legacy 를 하나만 주면 목록이 비어 {@code publishAll} 로
     * 아무것도 넘어가지 않아, {@code continue} 를 지워도 초록이 된다(2026-09-23 주입 실측).
     * 그 회귀가 나가면 컨슈머 멱등 키 {@code (token_id, issued_at)} 의 절반이 null 인 이벤트가
     * 발행돼 <b>같은 토큰의 두 번째 행 + 과금 1건</b>이 생긴다.
     */
    @Test
    @DisplayName("🔴 issuedAt 없는 건은 목록에서 빠진다 — 정상 건만 publishAll로 넘어간다")
    void legacyRecordIsExcludedFromPublishedList() {
        givenAdmit(false, legacyRecord("t1"), record("t2"));
        when(eventPublisher.publishAll(anyList())).thenReturn(0);

        service.admit(TENANT_ID, QUEUE_ID, 2, "req-1");

        ArgumentCaptor<java.util.List<com.sonix.queue.domain.queue.EnqueueEvent>> captor =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(eventPublisher).publishAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(com.sonix.queue.domain.queue.EnqueueEvent::tokenId)
                .containsExactly("t2");
        assertThat(requests("error")).as("건너뛴 건은 여전히 error다").isEqualTo(1.0);
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

    /**
     * 🔑 <b>이 단정이 2026-09-23 에 뒤집혔다.</b> 예전에는 중간 1건이 실패하면 남은 전부를
     * 건너뛰어(첫 실패에서 break) 3명 전원이 원장을 잃었다. 지금은 {@code publishAll} 이
     * 전량 보낸 뒤 한 번에 기다리고 <b>실패한 건만</b> 센다 — 폭발 반경이 N → 1 이다.
     */
    @Test
    @DisplayName("🔴 중간 1건이 실패해도 나머지는 발행된다 — 실패 건수만 센다(폭발 반경 N→1)")
    void countsOnlyActualFailures() {
        givenAdmit(false, record("t1"), record("t2"), record("t3"));
        // 3건을 한 번에 넘기고, 그중 1건만 실패한 상황
        when(eventPublisher.publishAll(argThat(list -> list.size() == 3))).thenReturn(1);

        service.admit(TENANT_ID, QUEUE_ID, 3, "req-1");

        assertThat(requests("error")).isEqualTo(1.0);
        // 🪤 $value(요청 건수)와 영향 사용자 수는 다르다 — 여기서 1건 대 **1명**이다
        //    (예전 구조에서는 같은 상황이 3명이었다).
        assertThat(tokensIssued()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("발행이 전부 성공하면 error는 0 — 한 번의 publishAll로 끝난다")
    void publishesOnceForWholeBatch() {
        givenAdmit(false, record("t1"), record("t2"), record("t3"));
        when(eventPublisher.publishAll(anyList())).thenReturn(0);

        service.admit(TENANT_ID, QUEUE_ID, 3, "req-1");

        assertThat(requests("error")).isZero();
        assertThat(requests("ok")).isEqualTo(1.0);
        verify(eventPublisher, times(1)).publishAll(argThat(list -> list.size() == 3));
        verify(eventPublisher, never()).publish(any());   // 건별 루프로 돌아가면 빨개진다
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
