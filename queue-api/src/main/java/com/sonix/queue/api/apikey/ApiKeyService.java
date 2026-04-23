package com.sonix.queue.api.apikey;

import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.common.util.RawKeyGenerator;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyHasher;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public ApiKeyIssueResponse issueApiKey(Long tenantId) {
        // 1. rawKey 생성 (RawKeyGenerator)
        String rawKey = RawKeyGenerator.generate();
        // 2. SHA-256 해싱 (ApiKeyHasher)
        String keyHash = ApiKeyHasher.hash(rawKey);
        // 3. ApiKey.create(tenantId, keyHash)
        ApiKey apiKey = ApiKey.create(tenantId, keyHash);
        // 4. apiKeyRepository.save(apiKey)
        apiKeyRepository.save(apiKey);
        // 5. ApiKeyIssueResponse.of(apiKeyId, rawKey) 반환
        return ApiKeyIssueResponse.of(apiKey.getApiKeyId(), rawKey);
    }
}
