package com.sonix.queue.api.apikey;

import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.security.TenantAuth;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

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

    @Test
    @DisplayName("POST /api-keys → 200 발급 성공")
    void issueApiKey_success() throws Exception {
        // given
        when(apiKeyService.issueApiKey(anyLong()))
                .thenReturn(ApiKeyIssueResponse.of("ak_test1234", "sk_live_abc123"));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/me/api-keys")
                                .requestAttr("org.springframework.security.web.authentication.WebAuthenticationDetails",
                                        new TenantAuth(1L, "t_test1234"))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyId").value("ak_test1234"))
                .andExpect(jsonPath("$.data.rawKey").value("sk_live_abc123"))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("DELETE /api-keys/:id → 200 Revoke 성공")
    void revokeApiKey_success() throws Exception {
        // given
        doNothing().when(apiKeyService).revokeApiKey(anyLong(), anyString());

        // when & then
        mockMvc.perform(
                        delete("/api/v1/tenants/me/api-keys/ak_test1234")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("DELETE /api-keys/:id → 404 존재하지 않는 Key")
    void revokeApiKey_not_found() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.API_KEY_NOT_FOUND))
                .when(apiKeyService).revokeApiKey(anyLong(), anyString());

        // when & then
        mockMvc.perform(
                        delete("/api/v1/tenants/me/api-keys/ak_notexist")
                )
                .andExpect(status().isNotFound());
    }


}