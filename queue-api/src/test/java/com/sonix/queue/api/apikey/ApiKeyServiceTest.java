package com.sonix.queue.api.apikey;

import com.sonix.queue.api.apikey.dto.ApiKeyIssueResponse;
import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyHasher;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import com.sonix.queue.domain.apikey.ApiKeyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    @DisplayName("API Key 발급 성공")
    void issueApiKey_success() {
        // given
        when(apiKeyRepository.save(any(ApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        ApiKeyIssueResponse response = apiKeyService.issueApiKey(1L);

        // then
        assertNotNull(response);
        assertTrue(response.getRawKey().startsWith("sk_live_"));
        assertTrue(response.getApiKeyId().startsWith("ak_"));
        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    @DisplayName("API Key Revoke 성공")
    void revokeApiKey_success() {
        // given
        ApiKey apiKey = ApiKey.create(1L, ApiKeyHasher.hash("sk_live_test"));

        when(apiKeyRepository.findByApiKeyId("ak_test1234")).thenReturn(Optional.of(apiKey));

        // when
        apiKeyService.revokeApiKey(1L, "ak_test1234");

        // then
        assertEquals(ApiKeyStatus.REVOKED, apiKey.getStatus());
        assertNotNull(apiKey.getRevokedAt());
        verify(apiKeyRepository).save(any(ApiKey.class));
    }

    @Test
    @DisplayName("API Key Revoke - 존재하지 않는 Key")
    void revokeApiKey_not_found() {
        // given
        when(apiKeyRepository.findByApiKeyId("ak_notexist")).thenReturn(Optional.empty());

        // when & then
        assertThrows(BusinessException.class, () ->
                apiKeyService.revokeApiKey(1L, "ak_notexist"));
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("API Key Revoke - 본인 소유 아님")
    void revokeApiKey_not_owned() {
        // given
        ApiKey apiKey = ApiKey.create(999L, ApiKeyHasher.hash("sk_live_test"));

        when(apiKeyRepository.findByApiKeyId("ak_test1234")).thenReturn(Optional.of(apiKey));

        // when & then
        assertThrows(BusinessException.class, () ->
                apiKeyService.revokeApiKey(1L, "ak_test1234"));
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    @DisplayName("API Key Revoke - 이미 REVOKED 상태")
    void revokeApiKey_already_revoked() {
        // given
        ApiKey apiKey = ApiKey.create(1L, ApiKeyHasher.hash("sk_live_test"));
        apiKey.revoke();

        when(apiKeyRepository.findByApiKeyId("ak_test1234")).thenReturn(Optional.of(apiKey));

        // when & then
        assertThrows(IllegalStateException.class, () ->
                apiKeyService.revokeApiKey(1L, "ak_test1234"));
    }
}