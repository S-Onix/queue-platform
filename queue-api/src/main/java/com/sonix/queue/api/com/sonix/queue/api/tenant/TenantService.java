package com.sonix.queue.api.com.sonix.queue.api.tenant;

import com.sonix.queue.api.com.sonix.queue.api.tenant.dto.LoginRequest;
import com.sonix.queue.api.com.sonix.queue.api.tenant.dto.LoginResponse;
import com.sonix.queue.api.com.sonix.queue.api.tenant.dto.SignupRequest;
import com.sonix.queue.api.com.sonix.queue.api.tenant.dto.TenantResponse;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.tenant.PasswordHasher;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TenantService {
    private final TenantRepository tenantRepository;
    private final PasswordHasher passwordHasher;

    public TenantService(TenantRepository tenantRepository, PasswordHasher passwordHasher) {
        this.tenantRepository = tenantRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public TenantResponse signup(SignupRequest request) {
        boolean isExist = tenantRepository.existsByEmail(request.getEmail());
        if (isExist) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String hash = passwordHasher.hash(request.getPassword());
        Tenant tenant = Tenant.create(request.getEmail(), hash, request.getName());
        Tenant saved = tenantRepository.save(tenant);
        return TenantResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Tenant tenant = tenantRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));

        if(!passwordHasher.matches(request.getPassword(), tenant.getPasswordHash())){
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        return LoginResponse.from(tenant);
    }
}
