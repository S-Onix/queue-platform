package com.sonix.queue.api.queue;

import com.sonix.queue.api.security.ApiKeyAuthenticationFilter;
import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.security.RateLimitFilter;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.AdmitRef;
import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenRepository;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * admit / verify / complete HTTP 계약 통합 테스트 (FRS §6.4~§6.6).
 *
 * <p>다른 컨트롤러 테스트와 달리 <b>{@code QueueEngineService}를 목킹하지 않는다.</b>
 * 검증 대상의 절반이 서비스 안에 있기 때문이다 — "Kafka 발행이 실패해도 200",
 * "Redis 미스면 DB fallback", "0행이면 404"는 컨트롤러를 목으로 막으면 아무것도 안 남는다.
 * 그래서 컨트롤러 → 실제 서비스 → <b>목킹된 포트</b>(QueueEngine·TokenRepository·발행자)까지
 * 한 번에 태운다.
 *
 * <p>{@code @Transactional}은 이 슬라이스에 트랜잭션 매니저가 없어 프록시되지 않는다.
 * 여기서 검증하는 것은 트랜잭션 경계가 아니라 HTTP 계약이므로 문제가 없다.
 */
@WebMvcTest(QueueEngineController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AdmitApiTest.RealServiceConfig.class)
class AdmitApiTest {

    private static final long TENANT_ID = 7L;
    private static final String QUEUE_ID = "q_dev_admit";
    private static final long NOW = 1_700_000_000_000L;

    @Autowired private MockMvc mockMvc;

    @MockBean private QueueRepository queueRepository;
    @MockBean private TokenRepository tokenRepository;
    @MockBean private QueueEngine queueEngine;
    @MockBean private EnqueueEventPublisher eventPublisher;
    // 필터는 addFilters=false로 꺼지지만 빈 자체는 컨텍스트가 요구한다 (기존 컨트롤러 테스트와 동일)
    @MockBean private JwtProvider jwtProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private RateLimitFilter rateLimitFilter;
    @MockBean private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    static class RealServiceConfig {
        @Bean
        QueueEngineService queueEngineService(QueueRepository queueRepository, TokenRepository tokenRepository,
                                              QueueEngine queueEngine, EnqueueEventPublisher eventPublisher) {
            return new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher,
                    Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        }
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new TenantAuth(TENANT_ID, "t_test1234"), null, List.of()));
        when(queueRepository.findByQueueId(QUEUE_ID)).thenReturn(Optional.of(ownedQueue()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** tenantId는 create()가 안 채우므로 reconstruct 대신 create + 목 조합으로 맞춘다. */
    private static Queue ownedQueue() {
        return Queue.create(TENANT_ID, "런칭 대기열", 100_000, null, null);
    }

    private static Token token(TokenStatus status, String admitToken) {
        return Token.reconstruct(1L, "tok_a", QUEUE_ID, TENANT_ID, "0190e2c1-user", 42L,
                status, null, admitToken,
                LocalDateTime.ofEpochSecond(1_700_000, 0, ZoneOffset.UTC), null);
    }

    // ── §6.4 Admit ──

    @Test
    @DisplayName("admit → 200, admitted 목록 반환 + ADMITTED가 tokenId 키로 발행된다")
    void admit_success() throws Exception {
        when(queueEngine.admit(QUEUE_ID, "req_1", 3, NOW)).thenReturn(new AdmitResult(false, List.of(
                new AdmitResult.AdmitRecord("u1", "tok_1", 10L, "adm_1", Instant.ofEpochMilli(1_000L)),
                new AdmitResult.AdmitRecord("u2", "tok_2", 11L, "adm_2", Instant.ofEpochMilli(2_000L)))));

        mockMvc.perform(post("/api/v1/queues/{q}/admit", QUEUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"requestId\":\"req_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admitted.length()").value(2))
                .andExpect(jsonPath("$.data.admitted[0].tokenId").value("tok_1"))
                .andExpect(jsonPath("$.data.admitted[0].identifier").value("u1"))
                .andExpect(jsonPath("$.data.admitted[0].seq").value(10))
                .andExpect(jsonPath("$.data.admitted[0].admitToken").value("adm_1"));

        ArgumentCaptor<EnqueueEvent> captor = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(e -> {
            // 판별 필드가 없으면 컨슈머가 조용히 enqueue로 적재한다 (§80).
            assertThat(e.eventType()).isEqualTo("ADMITTED");
            assertThat(e.queueId()).isEqualTo(QUEUE_ID);
            assertThat(e.tenantId()).isEqualTo(TENANT_ID);
            assertThat(e.issuedAt()).isNotNull();
        });
        assertThat(captor.getAllValues()).extracting(EnqueueEvent::tokenId)
                .containsExactly("tok_1", "tok_2");
    }

    @Test
    @DisplayName("🔴 Kafka 발행이 실패해도 200이다 — Lua가 이미 커밋돼 되돌릴 수 없다")
    void admit_publishFails_still200() throws Exception {
        when(queueEngine.admit(QUEUE_ID, "req_1", 1, NOW)).thenReturn(new AdmitResult(false,
                List.of(new AdmitResult.AdmitRecord("u1", "tok_1", 10L, "adm_1", Instant.ofEpochMilli(1_000L)))));
        doThrow(new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE))
                .when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/v1/queues/{q}/admit", QUEUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"requestId\":\"req_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admitted[0].admitToken").value("adm_1"));
    }

    @Test
    @DisplayName("count 101 → 400. @Max(100)이 서비스 진입 전에 막는다")
    void admit_countOverLimit() throws Exception {
        mockMvc.perform(post("/api/v1/queues/{q}/admit", QUEUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":101,\"requestId\":\"req_1\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queueEngine);
    }

    @Test
    @DisplayName("남의 큐 → 403 QUEUE_NOT_OWNED. 대기열은 건드리지 않는다")
    void admit_notOwned() throws Exception {
        when(queueRepository.findByQueueId(QUEUE_ID)).thenReturn(Optional.of(Queue.create(999L, "남의 큐", 10, null, null)));

        mockMvc.perform(post("/api/v1/queues/{q}/admit", QUEUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"requestId\":\"req_1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorResponse.code").value("Q002"));

        verifyNoInteractions(queueEngine);
    }

    @Test
    @DisplayName("대기열이 비어 0건이어도 200이고, 발행할 것도 없다")
    void admit_empty() throws Exception {
        when(queueEngine.admit(QUEUE_ID, "req_1", 5, NOW)).thenReturn(new AdmitResult(false, List.of()));

        mockMvc.perform(post("/api/v1/queues/{q}/admit", QUEUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":5,\"requestId\":\"req_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admitted.length()").value(0));

        verifyNoInteractions(eventPublisher);
    }

    // ── §6.5 Verify ──

    @Test
    @DisplayName("verify Redis 히트 → 200 valid + identifier. DB는 아예 읽지 않는다")
    void verify_redisHit() throws Exception {
        // 값이 "tokenId|seq|issuedAt|identifier"라 신원도 이벤트 재료도 Redis에 이미 있다.
        // 예전처럼 tokenId로 DB 행을 찾으면 컨슈머 백로그 구간(= 적재 전)의 정상 토큰이 404가 된다.
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef(
                        "tok_a", 7L, Instant.ofEpochMilli(1_700_000_000_000L), "0190e2c1-user")));

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.identifier").value("0190e2c1-user"));

        verifyNoInteractions(tokenRepository);
    }

    @Test
    @DisplayName("verify 구 포맷(tokenId만) → 기존 DB 경로로 폴백 (롤링 배포 중 60초)")
    void verify_legacyValue_fallsBackToDb() throws Exception {
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef("tok_a", -1L, null, null)));
        when(tokenRepository.findByTokenId(QUEUE_ID, TENANT_ID, "tok_a"))
                .thenReturn(Optional.of(token(TokenStatus.ADMIT_ISSUED, "adm_1")));

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifier").value("0190e2c1-user"));

        verify(tokenRepository, never()).findAdmittedByAdmitToken(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("verify Redis 미스 → DB fallback (admitted_at 기준, 유효 창 60초)")
    void verify_dbFallback() throws Exception {
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1")).thenReturn(Optional.empty());
        when(tokenRepository.findAdmittedByAdmitToken(QUEUE_ID, TENANT_ID, "adm_1", 60))
                .thenReturn(Optional.of(token(TokenStatus.ADMIT_ISSUED, "adm_1")));

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifier").value("0190e2c1-user"));
    }

    @Test
    @DisplayName("verify Redis·DB 둘 다 미스 → 404 TK002. Redis·DB 쓰기 0회")
    void verify_notFound() throws Exception {
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_x")).thenReturn(Optional.empty());
        when(tokenRepository.findAdmittedByAdmitToken(QUEUE_ID, TENANT_ID, "adm_x", 60))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorResponse.code").value("TK002"));

        verify(queueEngine, never()).cleanupCompleted(anyString(), anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    // ── §6.6 Complete ──

    @Test
    @DisplayName("complete → 200 COMPLETED + Redis 정리 + COMPLETED 발행")
    void complete_success() throws Exception {
        LocalDateTime completedAt = LocalDateTime.now(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        when(tokenRepository.markCompleted(QUEUE_ID, TENANT_ID, "tok_a", "adm_1", completedAt, 300))
                .thenReturn(1);
        when(tokenRepository.findByTokenId(QUEUE_ID, TENANT_ID, "tok_a"))
                .thenReturn(Optional.of(token(TokenStatus.COMPLETED, "adm_1")));

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").exists());

        // admit-by-admit은 TTL 말고 삭제 경로가 여기뿐이다 — 빠지면 60초간 유령이 남는다.
        verify(queueEngine).cleanupCompleted(QUEUE_ID, "0190e2c1-user", "tok_a", "adm_1", 42L);

        ArgumentCaptor<EnqueueEvent> captor = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().tokenId()).isEqualTo("tok_a");
    }

    @Test
    @DisplayName("complete 0행(자격 없음·유효 창 초과) → 404 TK002. Redis 정리·발행 없음")
    void complete_noRow() throws Exception {
        when(tokenRepository.markCompleted(anyString(), anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(0);

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_wrong\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorResponse.code").value("TK002"));

        verify(queueEngine, never()).cleanupCompleted(anyString(), anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("complete 0행 + Redis 히트 → 200. 컨슈머가 ADMITTED를 아직 적재하지 않은 창")
    void complete_dbMiss_redisFallback() throws Exception {
        // 🔑 이 분기가 없던 동안 **정상 입장자가 404**였다 — markCompleted의 술어가 요구하는
        //    admit_token·admitted_at은 ADMITTED 이벤트를 컨슈머가 적재해야만 채워지기 때문이다.
        //    Redis 히트는 "60초 안에 admit됐다"를 키의 PX가 이미 증명한 것이고, 그 창(60초)은
        //    DB 창(300초)의 **부분집합**이라 통과시켜도 자격이 넓어지지 않는다.
        //
        // 🪤 **이 스텁이 빠지면 테스트는 그대로 초록인 채 분기를 안 탄다.** Mockito 기본값이
        //    Optional.empty()라 조용히 404 경로로 흐른다 — 2026-08-27 검토가 잡아낸 실제 구멍이다.
        when(tokenRepository.markCompleted(anyString(), anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(0);
        when(tokenRepository.findCompletedAt(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef(
                        "tok_a", 42L, Instant.ofEpochMilli(1_700_000_000_000L), "0190e2c1-user")));

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // 이벤트 재료가 AdmitRef 안에 전부 있다 — 이 경로는 DB를 다시 읽지 않는다.
        verify(tokenRepository, never()).findByTokenId(anyString(), anyLong(), anyString());
        verify(queueEngine).cleanupCompleted(QUEUE_ID, "0190e2c1-user", "tok_a", "adm_1", 42L);

        ArgumentCaptor<EnqueueEvent> captor = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("COMPLETED");
        assertThat(captor.getValue().seq()).isEqualTo(42L);
    }

    @Test
    @DisplayName("complete 폴백: admitToken이 가리키는 tokenId가 다르면 404 — 남의 토큰을 못 닫는다")
    void complete_redisFallback_tokenIdMismatch_404() throws Exception {
        // 폴백이 인가 판정을 겸한다. admitToken만 맞고 경로의 tokenId가 다르면 거절해야 한다 —
        // 아니면 admitToken 하나로 같은 큐의 아무 토큰이나 완료시킬 수 있다.
        when(tokenRepository.markCompleted(anyString(), anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(0);
        when(tokenRepository.findCompletedAt(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef(
                        "tok_OTHER", 99L, Instant.ofEpochMilli(1_700_000_000_000L), "0190e2c1-other")));

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorResponse.code").value("TK002"));

        verify(queueEngine, never()).cleanupCompleted(anyString(), anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("complete: Kafka 발행이 실패해도 200 — DB는 이미 status=2로 확정됐다")
    void complete_publishFails_still200() throws Exception {
        when(tokenRepository.markCompleted(anyString(), anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(1);
        when(tokenRepository.findByTokenId(QUEUE_ID, TENANT_ID, "tok_a"))
                .thenReturn(Optional.of(token(TokenStatus.COMPLETED, "adm_1")));
        doThrow(new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE)).when(eventPublisher).publish(any());

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    /**
     * 🔑 <b>verify 응답을 주는 시점이 완료다.</b> Platform의 책임은 답을 돌려주는 데까지이고,
     * 그 뒤 Tenant 안에서 벌어지는 일은 관측할 수도 책임질 수도 없다.
     *
     * <p>🔴 <b>그런데 DB를 쓰면 안 된다.</b> verify는 {@code @Transactional(readOnly = true)}라
     * Replica로 라우팅되고 Replica는 {@code super-read-only=ON}이다. 그래서 이벤트만 발행한다 —
     * {@code verifyNoInteractions(tokenRepository)}가 그 계약을 못박는다.
     */
    @Test
    @DisplayName("verify 성공 = 완료 — COMPLETED를 발행하되 DB는 건드리지 않는다")
    void verify_publishesCompleted_withoutTouchingDb() throws Exception {
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef(
                        "tok_a", 7L, Instant.ofEpochMilli(1_700_000_000_000L), "user-a")));

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_1"))
                .andExpect(status().isOk());

        ArgumentCaptor<EnqueueEvent> ev = ArgumentCaptor.forClass(EnqueueEvent.class);
        verify(eventPublisher).publish(ev.capture());
        assertThat(ev.getValue().eventType()).isEqualTo(TokenEventType.COMPLETED.name());
        assertThat(ev.getValue().tokenId()).isEqualTo("tok_a");
        assertThat(ev.getValue().seq()).isEqualTo(7L);
        assertThat(ev.getValue().issuedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));

        verifyNoInteractions(tokenRepository);
    }

    /**
     * 🪤 구 포맷(seq·issuedAt 없음)은 이벤트를 만들 수 없다. 그때도 <b>verify의 계약(identifier
     * 반환)은 지켜야 한다</b> — 완료 발행만 건너뛴다. 롤링 배포 60초 구간의 동작이다.
     */
    @Test
    @DisplayName("구 포맷(신원만 있는 값) — 완료 발행은 건너뛰고 identifier는 답한다")
    void verify_legacyTwoFieldValue_skipsCompletionButStillAnswers() throws Exception {
        when(queueEngine.findAdmitRefByAdmitToken(QUEUE_ID, "adm_1"))
                .thenReturn(Optional.of(new AdmitRef("tok_a", -1L, null, "user-a")));

        mockMvc.perform(post("/api/v1/queues/{q}/admit-tokens/{a}/verify", QUEUE_ID, "adm_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identifier").value("user-a"));

        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(tokenRepository);
    }

    /**
     * 🔴 <b>verify가 완료를 확정하게 되면서 생긴 경로다.</b> {@code verify → complete}를 둘 다 부르는
     * 정상 Tenant는 complete 시점에 이미 {@code status=2}라, {@code markCompleted}의 술어
     * ({@code status IN (0,1)})에 걸려 <b>0행</b>을 받는다.
     *
     * <p>이걸 400/404로 돌려주면 <b>아무 잘못 없는 통합이 깨진다.</b> 그리고 돌려주는 시각은
     * <b>처음 완료된 시각</b>이어야 한다 — 지금 시각을 주면 응답이 거짓이고 재시도마다 값이 바뀐다.
     */
    @Test
    @DisplayName("verify로 이미 완료된 토큰의 complete → 200 + 처음 완료 시각 (멱등)")
    void complete_alreadyCompletedByVerify_isIdempotent() throws Exception {
        LocalDateTime firstCompletedAt = LocalDateTime.of(2026, 8, 24, 1, 2, 3);
        when(tokenRepository.markCompleted(anyString(), anyLong(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(0);
        when(tokenRepository.findCompletedAt(QUEUE_ID, TENANT_ID, "tok_a", "adm_1"))
                .thenReturn(Optional.of(firstCompletedAt));

        mockMvc.perform(post("/api/v1/queues/{q}/tokens/{t}/complete", QUEUE_ID, "tok_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admitToken\":\"adm_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").value("2026-08-24T01:02:03"));

        // 이미 끝난 건이라 Redis를 다시 정리하거나 이벤트를 또 발행하지 않는다.
        verifyNoInteractions(eventPublisher);
    }
}
