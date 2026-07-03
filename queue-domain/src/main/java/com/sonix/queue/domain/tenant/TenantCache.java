package com.sonix.queue.domain.tenant;

import java.util.Optional;

/**
 * Redis 구현
 * @code infrastructure.cache.RedisTenantCache
 *
 * 주 사용처 : Plan 정보 조회시
 *
 * 사용 패턴 : Cache Aside (캐시에 존재? 캐시에서 가져오기 아니면 DB에서 가져오기)
 *
 * */
public interface TenantCache {

    /**
     * 캐시에서 Tenant 조회.
     *
     * @param tenantId 도메인 ID ({@code t_xxx} 형태)
     * @return 캐시 존재 시 Tenant, 미스 시 {@link Optional#empty()}
     */
    Optional<Tenant> get(String tenantId);

    /**
     * 캐시에 Tenant 저장.
     *
     * <p>TTL은 구현체 정책 (Sprint 5-D 기본 60초).
     * 저장 실패 시 예외를 던지지 않고 로그만 남김 (DB fallback 가능).
     *
     * @param tenant 저장할 도메인 객체
     */
    void put(Tenant tenant);

    /**
     * 특정 tenantId의 캐시 무효화.
     *
     * <p>Tenant 변경 시점(예: Plan 변경) 호출.
     * 캐시에 없어도 예외 X (idempotent 보장).
     *
     * @param tenantId 도메인 ID
     */
    void invalidate(String tenantId);
}
