package com.sonix.queue.api.tenant;

import com.sonix.queue.api.tenant.dto.*;
import com.sonix.queue.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {
    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/api/v1/tenants/signup")
    public ApiResponse<TenantResponse> signup(@RequestBody @Valid SignupRequest request){
        return ApiResponse.ok(tenantService.signup(request));
    }

    @PostMapping("/api/v1/tenants/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request){
        return ApiResponse.ok(tenantService.login(request));
    }

    @PostMapping("/api/v1/tenants/refresh")
    public ApiResponse<RefreshResponse> refresh(@RequestBody @Valid RefreshRequest request){
        return ApiResponse.ok(tenantService.refresh(request));
    }
}
