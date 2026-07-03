package com.sonix.queue.infrastructure.cache.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonix.queue.domain.tenant.Plan;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantStatus;

import java.time.LocalDateTime;

/**
 * Tenant 도메인 객체의 Jackson 직렬화/역직렬화 설정.
 *
 * <p>도메인 오염 방지를 위해 Tenant.java에는 Jackson 어노테이션을 직접 붙이지 않고
 * Mixin으로 분리 관리.
 *
 * <p>RedisConfig에서 ObjectMapper에 등록:
 * <pre>{@code
 * objectMapper.addMixIn(Tenant.class, TenantMixin.class);
 * }</pre>
 *
 * <p>이 클래스 자체는 인스턴스화되지 않음.
 * Jackson이 "Tenant 처리 시 이 클래스의 어노테이션 참조"만 사용.
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
            @JsonProperty("plan") Plan plan,
            @JsonProperty("createdAt") LocalDateTime createdAt
    ) {
        return null;  // 사용 안 됨, 시그니처만 중요
    }
}
