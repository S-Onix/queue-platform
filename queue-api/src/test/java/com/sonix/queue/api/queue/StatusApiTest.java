package com.sonix.queue.api.queue;

import com.sonix.queue.api.security.ApiKeyAuthenticationFilter;
import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.security.RateLimitFilter;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.PacingTier;
import com.sonix.queue.domain.queue.QueueBoard;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/queues/&#123;queueId&#125;/status} HTTP 계약 (FRS §6.3 ① / DECISIONS §79).
 *
 * <p>{@code AdmitApiTest}와 같은 방식으로 <b>서비스를 목킹하지 않는다</b> — 여기서 지켜야 할 것의
 * 절반이 직렬화 형태이기 때문이다. 특히 {@code pacing} 마지막 항의 상한은 <b>JSON {@code null}</b>
 * 이어야 하고({@code "그 이상 전부"}의 표현), {@code List.of}로 조립하면 그 자리에서 예외가 난다.
 */
@WebMvcTest(QueueEngineController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(StatusApiTest.RealServiceConfig.class)
class StatusApiTest {

    private static final String QUEUE_ID = "q_dev_status";

    @Autowired private MockMvc mockMvc;

    @MockBean private QueueRepository queueRepository;
    @MockBean private TokenRepository tokenRepository;
    @MockBean private QueueEngine queueEngine;
    @MockBean private EnqueueEventPublisher eventPublisher;

    // 필터는 addFilters=false로 꺼지지만 빈 자체는 컨텍스트가 요구한다
    @MockBean private JwtProvider jwtProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private RateLimitFilter rateLimitFilter;
    @MockBean private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    static class RealServiceConfig {
        @Bean
        QueueEngineService queueEngineService(QueueRepository queueRepository, TokenRepository tokenRepository,
                                              QueueEngine queueEngine, EnqueueEventPublisher eventPublisher) {
            return new QueueEngineService(queueRepository, tokenRepository, queueEngine, eventPublisher,
                    Clock.systemUTC(), 0L);
        }
    }

    @Test
    @DisplayName("전광판: lastAdmittedSeq + pacing 구간표. 마지막 상한은 JSON null(= 그 이상 전부)")
    void status_returnsWatermarkAndPacing() throws Exception {
        when(queueEngine.readStatus(QUEUE_ID))
                .thenReturn(Optional.of(new QueueBoard(47L, PacingTier.DEFAULT)));

        mockMvc.perform(get("/api/v1/queues/{queueId}/status", QUEUE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastAdmittedSeq").value(47))
                .andExpect(jsonPath("$.data.pacing.length()").value(5))
                .andExpect(jsonPath("$.data.pacing[0][0]").value(50))
                .andExpect(jsonPath("$.data.pacing[0][1]").value(2))
                .andExpect(jsonPath("$.data.pacing[4][0]").doesNotExist())   // null = 그 이상 전부
                .andExpect(jsonPath("$.data.pacing[4][1]").value(20))
                // §79가 뺀 필드들. 다시 들어오면 응답이 사람마다 달라져 분할이 무의미해진다.
                .andExpect(jsonPath("$.data.frontSeq").doesNotExist())
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.nextPollAfterSec").doesNotExist());
    }

    @Test
    @DisplayName("아무도 입장하지 않았으면 lastAdmittedSeq=0 — 콜드 스타트 폴백이 따로 없다")
    void status_coldStartIsZero() throws Exception {
        // rank = mySeq - 0 = mySeq. 그게 맞는 값이라 특별 취급이 필요 없다 (§79).
        when(queueEngine.readStatus(QUEUE_ID))
                .thenReturn(Optional.of(new QueueBoard(0L, PacingTier.DEFAULT)));

        mockMvc.perform(get("/api/v1/queues/{queueId}/status", QUEUE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastAdmittedSeq").value(0));
    }

    @Test
    @DisplayName("pacing 오버라이드가 있으면 그 표가 그대로 응답에 실린다 (운영 레버)")
    void status_pacingOverrideIsServed() throws Exception {
        when(queueEngine.readStatus(QUEUE_ID)).thenReturn(Optional.of(
                new QueueBoard(0L, List.of(new PacingTier(50L, 4), new PacingTier(null, 40)))));

        mockMvc.perform(get("/api/v1/queues/{queueId}/status", QUEUE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pacing.length()").value(2))
                .andExpect(jsonPath("$.data.pacing[1][1]").value(40));
    }

    @Test
    @DisplayName("미지 queueId는 404 Q001 — DB를 거치지 않는다")
    void status_unknownQueueIs404WithoutDb() throws Exception {
        when(queueEngine.readStatus("q_ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/queues/{queueId}/status", "q_ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorResponse.code").value("Q001"));

        // 인증이 없는 경로다(§79). 큐 존재 확인이 DB로 내려가면 임의 문자열만으로 MySQL을 때운다.
        // ⚠️ 이 테스트가 보증하는 것은 "DB를 안 탄다"까지다. Redis 왕복 수는 여기서 알 수 없다
        //    (queueEngine이 목이다). 실제로는 미지 queueId가 EXISTS×2 + MGET = 3왕복이고
        //    두 클러스터 8개 마스터로 퍼진다(2026-08-28 실측, QueueEngineController 주석 참조).
        verify(queueRepository, never()).findByQueueId(anyString());
    }
}
