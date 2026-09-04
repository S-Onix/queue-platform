package com.sonix.queue.domain.tenant;

import java.util.Optional;

/**
 * Redis 구현
 * @code infrastructure.cache.RedisTenantCache
 *
 * 주 사용처 : Rate Limit이 매 인증 요청마다 테넌트를 찾을 때 (버킷 키에 tenantId 문자열이 필요하다).
 * ⚠️ 한도 자체는 상수라 캐시가 한도를 바꾸지는 않는다 (§88 — 등급제 제거).
 *
 * 사용 패턴 : Cache Aside (캐시에 존재? 캐시에서 가져오기 아니면 DB에서 가져오기)
 *
 * */
public interface TenantCache {

    /**
     * 캐시에서 Tenant 조회.
     *
     * <p><b>조회 키가 PK(Long)인 이유:</b> 이 캐시를 쓰는 Rate Limit 경로는 인증 방식이
     * 두 가지인데(JWT / API-Key), 두 경로가 <b>공통으로 확보하는 식별자는 PK 하나</b>다.
     * API-Key는 {@code api_keys.tenant_id}(PK)만 들고 있어 {@code t_xxx} 형태를 알려면
     * Tenant를 한 번 더 조회해야 한다.
     *
     * @param id Tenant PK
     * @return 캐시 존재 시 Tenant, 미스 시 {@link Optional#empty()}
     */
    Optional<Tenant> get(Long id);

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
     * <p>Tenant 변경 시점 호출. ⚠️ <b>현재 호출자 0건이다</b>(§4-1) — 캐시에 담기는 것 중
     * 60초 안에 바뀌는 필드가 없어서다. plan이 사라진 뒤로는 더 그렇다(§88).
     * 캐시에 없어도 예외 X (idempotent 보장).
     *
     * @param id Tenant PK
     */
    void invalidate(Long id);
}
