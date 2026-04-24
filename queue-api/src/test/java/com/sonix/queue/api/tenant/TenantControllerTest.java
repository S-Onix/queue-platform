package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.JwtAuthenticationFilter;
import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.tenant.dto.LoginRequest;
import com.sonix.queue.api.tenant.dto.LoginResponse;
import com.sonix.queue.api.tenant.dto.SignupRequest;
import com.sonix.queue.api.tenant.dto.TenantResponse;
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

    @Test
    @DisplayName("POST /signup → 200")
    void signup_success() throws Exception {
        // given
        TenantResponse response = TenantResponse.from(
                Tenant.create("test@email.com", "hash", "테스트"));

        when(tenantService.signup(any(SignupRequest.class))).thenReturn(response);

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"1234\",\"name\":\"테스트\"}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("test@email.com"))
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
                                .content("{\"email\":\"test@email.com\",\"password\":\"1234\",\"name\":\"테스트\"}")
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

    @Test
    @DisplayName("POST /login 없는 이메일 → 404")
    void login_not_found() throws Exception {
        // given
        when(tenantService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.TENANT_NOT_FOUND));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"no@email.com\",\"password\":\"1234\"}")
                )
                .andExpect(status().isNotFound());  // 404
    }

    @Test
    @DisplayName("POST /login 잘못된 비밀번호 → 401")
    void login_invalid_password() throws Exception {
        // given
        when(tenantService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_PASSWORD));

        // when & then
        mockMvc.perform(
                        post("/api/v1/tenants/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"test@email.com\",\"password\":\"wrong\"}")
                )
                .andExpect(status().isUnauthorized());  // 401
    }
}