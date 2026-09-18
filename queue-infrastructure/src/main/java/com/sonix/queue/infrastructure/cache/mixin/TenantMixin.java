package com.sonix.queue.infrastructure.cache.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantStatus;

import java.time.LocalDateTime;

/**
 * 이유: {@link Tenant} 의 Jackson 직렬화 설정.
 * 문제: 도메인에 Jackson 어노테이션을 붙이면 <b>헥사고날이 깨진다</b>(queue-domain 은 순수 Java 다).
 * 해결: Mixin 으로 분리하고 {@code RedisConfig} 의 ObjectMapper 에 {@code addMixIn} 으로 등록한다.
 * 🪤 이 클래스는 인스턴스화되지 않는다 — Jackson 이 <b>어노테이션만</b> 참조한다.
 *
 * @author sonix
 */
public abstract class TenantMixin {

    @JsonCreator
    public static Tenant reconstruct(
            @JsonProperty("id") Long id,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("email") String email,
            @JsonProperty("passwordHash") String passwordHash,
            @JsonProperty("name") String name,
            @JsonProperty("status") TenantStatus status,
            @JsonProperty("createdAt") LocalDateTime createdAt
    ) {
        return null;  // 사용 안 됨, 시그니처만 중요
    }
}
