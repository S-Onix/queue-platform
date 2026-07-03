package com.sonix.queue.infrastructure.cache.mixin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.tenant.Tenant;

public class CacheMixinRegistrar {
    private CacheMixinRegistrar() {
        // 인스턴스화 방지
    }
    /**
     * 모든 캐시용 Mixin을 ObjectMapper에 등록.
     *
     * @param mapper 캐시 전용 ObjectMapper
     */
    public static void registerAll(ObjectMapper mapper) {
        mapper.addMixIn(Tenant.class, TenantMixin.class);
        mapper.addMixIn(ApiKey.class, ApiKeyMixin.class);
    }
}
