package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.ApiKeyAuthenticationFilter;
import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.security.RateLimitFilter;
import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.tenant.Tenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantController.class)
@AutoConfigureMockMvc(addFilters = false)
class TenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private JwtProvider jwtProvider;              // ← 추가

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @MockBean
    private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Test
    @DisplayName("POST /signup → 200")
    void signup_success() throws Exception {
        // given
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");

        TenantResponse response = TenantResponse.from(tenant);

        when(tenantService.signup(any(SignupRequest.class))).thenReturn(response);

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"Str0ngPassw0rd!\",\"name\":\"테스트\"}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("test@email.com"))
                .andExpect(jsonPath("$.data.name").value("테스트"))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /signup 중복 이메일 → 409")
    void signup_duplicate_email() throws Exception {
        // given
        when(tenantService.signup(any(SignupRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"Str0ngPassw0rd!\",\"name\":\"테스트\"}")
                )
                .andExpect(status().isConflict());  // 409
    }

    @Test
    @DisplayName("POST /login → 200 + JWT")
    void login_success() throws Exception {
        // given
        when(tenantService.login(any(LoginRequest.class)))
                .thenReturn(LoginResponse.of("mock-access", "mock-refresh"));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"1234\"}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("mock-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("mock-refresh"));
    }

    /**
     * 🔴 <b>없는 이메일도 401이다.</b> 예전엔 404(T002)라 <b>응답 코드 한 번으로 계정 존재 여부가
     * 새어나갔다</b> — 공격자가 그걸로 실존 계정 목록을 만든 뒤 10/분 예산을 그쪽에만 쓴다.
     *
     * <p>이 테스트의 옛 이름이 "없는 이메일 → 404"였다. <b>테스트가 누출을 명세로 못박고 있었다.</b>
     */
    @Test
    @DisplayName("POST /login 없는 이메일 → 401 (틀린 비밀번호와 같은 응답 — 계정 열거 차단)")
    void login_unknown_email_is_indistinguishable() throws Exception {
        // given
        when(tenantService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"no@email.com\",\"password\":\"1234\"}")
                )
                .andExpect(status().isUnauthorized());  // 401 — 아래 케이스와 같아야 한다
    }

    @Test
    @DisplayName("POST /login 잘못된 비밀번호 → 401 (위 케이스와 구분 불가)")
    void login_invalid_password() throws Exception {
        // given
        when(tenantService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"wrong\"}")
                )
                .andExpect(status().isUnauthorized());  // 401
    }
}