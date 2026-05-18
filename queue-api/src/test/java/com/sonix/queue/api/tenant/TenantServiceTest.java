package com.sonix.queue.api.tenant;

import com.sonix.queue.api.security.JwtProvider;
import com.sonix.queue.api.tenant.dto.LoginRequest;
import com.sonix.queue.api.tenant.dto.LoginResponse;
import com.sonix.queue.api.tenant.dto.SignupRequest;
import com.sonix.queue.api.tenant.dto.TenantResponse;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.domain.tenant.PasswordHasher;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.domain.tenant.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private TenantService tenantService;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("회원가입 성공")
    void signup_success() {
        // given
        SignupRequest request = new SignupRequest();
        request.setEmail("test@email.com");
        request.setPassword("1234");
        request.setName("테스트");

        when(tenantRepository.existsByEmail("test@email.com")).thenReturn(false);
        when(passwordHasher.hash("1234")).thenReturn("hashed_pw");
        when(tenantRepository.save(any(Tenant.class)))
                .thenReturn(Tenant.create("test@email.com", "hashed_pw", "테스트"));

        // when
        TenantResponse response = tenantService.signup(request);

        // then
        assertNotNull(response);
        assertEquals("test@email.com", response.getEmail());
        verify(tenantRepository).save(any(Tenant.class));
    }

    @Test
    @DisplayName("회원가입 - 중복 이메일 예외")
    void signup_duplicate_email() {
        // given
        SignupRequest request = new SignupRequest();
        request.setEmail("test@email.com");
        request.setPassword("1234");
        request.setName("테스트");

        when(tenantRepository.existsByEmail("test@email.com")).thenReturn(true);

        // when & then
        assertThrows(BusinessException.class, () -> tenantService.signup(request));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("login 성공")
    void login_success() {
        //given
        LoginRequest request = new LoginRequest();
        request.setEmail("test@email.com");
        request.setPassword("1234");

        Tenant tenant = Tenant.reconstruct(
                1L, "t_test1234"
                , "test@email.com"
                , "hashed_pw", "테스트"
                , TenantStatus.ACTIVE, LocalDateTime.now()
        );

        when(tenantRepository.findByEmail("test@email.com")).thenReturn(Optional.of(tenant));
        when(passwordHasher.matches("1234", "hashed_pw")).thenReturn(true);
        when(jwtProvider.generateAccessToken(1L, "t_test1234")).thenReturn("mock-access");
        when(jwtProvider.generateRefreshToken(1L)).thenReturn("mock-refresh");

        LoginResponse response = tenantService.login(request);

        assertEquals("mock-access", response.getAccessToken());
        assertEquals("mock-refresh", response.getRefreshToken());

    }
}