package com.sonix.queue.api.apikey;

import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.common.util.RawKeyGenerator;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyCache;
import com.sonix.queue.domain.apikey.ApiKeyHasher;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyCache apiKeyCache;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, ApiKeyCache apiKeyCache) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyCache = apiKeyCache;
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

    @Transactional
    public void revokeApiKey(Long tenantId, String apiKeyId) {
        ApiKey apiKey = apiKeyRepository.findByApiKeyId(apiKeyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));

        if(!tenantId.equals(apiKey.getTenantId())) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_OWNED);
        }

        apiKey.revoke();
        apiKeyRepository.save(apiKey);

        // 🔴 캐시를 지우지 않으면 폐기한 키가 최대 60초 더 산다.
        //
        // 인증 필터는 `apikey:{keyHash}`를 먼저 보고(POSITIVE_TTL 60초), 히트하면 DB를 안 읽는다.
        // 캐시에 든 객체는 revoke 이전 스냅샷이라 `isActive()`도 통과시킨다 — 상태 검사로는 못 막는다.
        // 캐시가 Redis 공유라 이 한 줄이 **전 인스턴스에 즉시** 전파된다.
        //
        // ⚠️ 트랜잭션 안에서 지운다. 커밋 전에 다른 요청이 DB를 읽어 옛 ACTIVE를 다시 캐싱하는
        //    좁은 창이 남지만, 그건 **지금과 같은 상태(최대 60초)**로 돌아갈 뿐 더 나빠지지 않는다.
        //    반대로 롤백되면 캐시만 비는데, 다음 요청이 DB에서 읽어 다시 채운다 — 안전한 방향이다.
        //    창까지 없애려면 @TransactionalEventListener(AFTER_COMMIT)이지만 그건 한 줄이 아니고,
        //    지금 이 한 줄이 없애는 위험(폐기가 60초 지연)에 비해 남는 위험이 훨씬 작다.
        apiKeyCache.invalidate(apiKey.getKeyHash());
    }


}
