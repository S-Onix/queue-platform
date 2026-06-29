package com.sonix.queue.api.tenant;

import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {
    private final TenantService tenantService;
    private final SignupFacade signupFacade;

    public TenantController(TenantService tenantService, SignupFacade signupFacade) {
        this.tenantService = tenantService;
        this.signupFacade = signupFacade;
    }

    @PostMapping("/api/v1/tenants/signup")
    public ApiResponse<SignupResponse> signup(@RequestBody @Valid SignupRequest request){
        return ApiResponse.ok(signupFacade.signup(request));
    }

    @PostMapping("/api/v1/tenants/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request){
        return ApiResponse.ok(tenantService.login(request));
    }

    @PostMapping("/api/v1/tenants/refresh")
    public ApiResponse<RefreshResponse> refresh(@RequestBody @Valid RefreshRequest request){
        return ApiResponse.ok(tenantService.refresh(request));
    }

    @PostMapping("/api/v1/tenants/logout")
    public ApiResponse<Void> logout(@RequestBody @Valid RefreshRequest request){
        tenantService.logout(request.getToken());
        return ApiResponse.ok(null);
    }
}
