package com.sonix.queue.api.queue;

import com.sonix.queue.api.queue.dto.QueueCreateRequest;
import com.sonix.queue.api.queue.dto.QueueResponse;
import com.sonix.queue.api.queue.dto.QueueUpdateRequest;
import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class QueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueueService queueService;

    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private QueueResponse mockResponse() {
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        return QueueResponse.from(queue);
    }

    @BeforeEach
    void setUp() {
        // 매 테스트 전에 인증 정보 설정
        TenantAuth tenantAuth = new TenantAuth(1L, "t_test1234");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(tenantAuth, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── 생성 ──

    @Test
    @DisplayName("POST /queues → 200 생성 성공")
    void createQueue_success() throws Exception {
        // given
        when(queueService.createQueue(anyLong(), any(QueueCreateRequest.class)))
                .thenReturn(mockResponse());

        // when & then
        mockMvc.perform(
                        post("/api/v1/queues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"이벤트 대기열\",\"maxCapacity\":100000}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("이벤트 대기열"))
                .andExpect(jsonPath("$.data.maxCapacity").value(100000))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /queues → 409 이름 중복")
    void createQueue_duplicate_name() throws Exception {
        // given
        when(queueService.createQueue(anyLong(), any(QueueCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_QUEUE_NAME));

        // when & then
        mockMvc.perform(
                        post("/api/v1/queues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"이벤트 대기열\",\"maxCapacity\":100000}")
                )
                .andExpect(status().isConflict());
    }

    // ── 조회 ──

    @Test
    @DisplayName("GET /queues/:id → 200 조회 성공")
    void getQueue_success() throws Exception {
        // given
        when(queueService.getQueue(anyLong(), anyString()))
                .thenReturn(mockResponse());

        // when & then
        mockMvc.perform(
                        get("/api/v1/queues/q_test1234")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("이벤트 대기열"));
    }

    @Test
    @DisplayName("GET /queues/:id → 404 존재하지 않음")
    void getQueue_not_found() throws Exception {
        // given
        when(queueService.getQueue(anyLong(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.QUEUE_NOT_FOUND));

        // when & then
        mockMvc.perform(
                        get("/api/v1/queues/q_notexist")
                )
                .andExpect(status().isNotFound());
    }

    // ── 수정 ──

    @Test
    @DisplayName("PATCH /queues/:id → 200 이름 변경 성공")
    void updateQueue_success() throws Exception {
        // given
        Queue updated = Queue.create(1L, "새 이름", 100000, null, null);
        when(queueService.updateQueue(anyLong(), anyString(), any(QueueUpdateRequest.class)))
                .thenReturn(QueueResponse.from(updated));

        // when & then
        mockMvc.perform(
                        patch("/api/v1/queues/q_test1234")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"새 이름\"}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 이름"));
    }

    // ── 정지 ──

    @Test
    @DisplayName("POST /queues/:id/pause → 200 정지 성공")
    void pauseQueue_success() throws Exception {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        queue.pause();
        when(queueService.pauseQueue(anyLong(), anyString()))
                .thenReturn(QueueResponse.from(queue));

        // when & then
        mockMvc.perform(
                        post("/api/v1/queues/q_test1234/pause")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));
    }

    // ── 재개 ──

    @Test
    @DisplayName("POST /queues/:id/resume → 200 재개 성공")
    void resumeQueue_success() throws Exception {
        // given
        when(queueService.resumeQueue(anyLong(), anyString()))
                .thenReturn(mockResponse());

        // when & then
        mockMvc.perform(
                        post("/api/v1/queues/q_test1234/resume")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    // ── 삭제 ──

    @Test
    @DisplayName("DELETE /queues/:id → 200 삭제 성공")
    void deleteQueue_success() throws Exception {
        // given
        Queue queue = Queue.create(1L, "이벤트 대기열", 100000, null, null);
        queue.pause();
        queue.delete();
        when(queueService.deleteQueue(anyLong(), anyString()))
                .thenReturn(QueueResponse.from(queue));

        // when & then
        mockMvc.perform(
                        delete("/api/v1/queues/q_test1234")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));
    }

    @Test
    @DisplayName("DELETE /queues/:id → 403 본인 소유 아님")
    void deleteQueue_not_owned() throws Exception {
        // given
        when(queueService.deleteQueue(anyLong(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.QUEUE_NOT_OWNED));

        // when & then
        mockMvc.perform(
                        delete("/api/v1/queues/q_test1234")
                )
                .andExpect(status().isForbidden());
    }
}