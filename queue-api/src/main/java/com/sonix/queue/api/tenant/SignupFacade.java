package com.sonix.queue.api.tenant;

import com.sonix.queue.api.apikey.ApiKeyService;
import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.api.tenant.dto.SignupRequest;
import com.sonix.queue.api.tenant.dto.SignupResponse;
import com.sonix.queue.domain.tenant.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 흐름 조합 Facade.
 *
 * <p>책임 분리:
 * <ul>
 *   <li>{@link TenantService#signup}: Tenant 생성만</li>
 *   <li>{@link ApiKeyService#issueApiKey}: ApiKey 발급만</li>
 *   <li>{@link SignupFacade}: 두 Service를 조합해 가입 응답 생성</li>
 * </ul>
 *
 * <p>트랜잭션: Facade 한 곳에서 관리 (Tenant + ApiKey 원자성 보장).
 * ApiKey 발급 실패 시 Tenant도 롤백.
 */
@Service
public class SignupFacade {
    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;

    public SignupFacade(TenantService tenantService, ApiKeyService apiKeyService) {
        this.tenantService = tenantService;
        this.apiKeyService = apiKeyService;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        Tenant tenant = tenantService.signup(request);
        ApiKeyIssueResponse apiKey = apiKeyService.issueApiKey(tenant.getId());

        return SignupResponse.of(tenant, apiKey);
    }
}
