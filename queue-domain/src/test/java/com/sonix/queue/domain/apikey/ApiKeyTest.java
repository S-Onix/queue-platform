package com.sonix.queue.domain.apikey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ApiKeyTest {

    @Test
    @DisplayName("생성시 status = ACTIVE")
    void create_status_is_active(){
        Long tenantId = 1111000L;
        String keyHash = "test";
        ApiKey apiKey = ApiKey.create(tenantId, keyHash);

        assertEquals(ApiKeyStatus.ACTIVE, apiKey.getStatus());
    }

    @Test
    @DisplayName("생성시 apiKey는 ak_로 시작")
    void create_tenantId_starts_with_t(){
        ApiKey apiKey = ApiKey.create(1111111L, "test");

        assertTrue(apiKey.getApiKeyId().startsWith("ak_"));
    }

    @Test
    @DisplayName("revoke 성공")
    void revoke_success(){
        ApiKey apiKey = ApiKey.create(1111111L, "test");
        apiKey.revoke();
        assertEquals(ApiKeyStatus.REVOKED, apiKey.getStatus());
    }

    @Test
    @DisplayName("revoke 성공시 revoked_at 생성")
    void revoke_success_and_revoked_at_is_not_null(){
        ApiKey apiKey = ApiKey.create(1111111L, "test");
        apiKey.revoke();

        assertNotNull(apiKey.getRevokedAt());
    }

    @Test
    @DisplayName("matchesHash - 일치")
    void matchesHash_success() {
        String rawKey = "sk_live_test123";
        String keyHash = ApiKeyHasher.hash(rawKey);
        ApiKey apiKey = ApiKey.create(1L, keyHash);

        assertTrue(apiKey.matchesHash(rawKey));
    }

    @Test
    @DisplayName("matchesHash - 불일치")
    void matchesHash_fail() {
        String keyHash = ApiKeyHasher.hash("sk_live_test123");
        ApiKey apiKey = ApiKey.create(1L, keyHash);

        assertFalse(apiKey.matchesHash("wrong_key"));
    }
}
