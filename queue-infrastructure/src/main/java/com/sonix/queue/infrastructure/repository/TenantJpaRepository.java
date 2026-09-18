package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.infrastructure.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, Long> {

/**
 * 이유: PK 조회. 🔴 <b>상속받은 {@code findById} 를 쓰지 마라 — 그건 replica 로 간다.</b>
 * 원인: 상속 CRUD 에는 클래스 레벨 {@code readOnly} 가 걸려 있다 — 기준은 §4-3 그대로다.
 * 🔴 <b>왜 master 여야 하나</b>(실측 2026-09-01): 이 조회는 {@code RateLimitFilter} 가 <b>모든 인증 요청</b>
 *    마다 타는 경로(캐시 미스)라, replica 가 죽는 순간 <b>인증된 API 전체가 500</b> 이 된다.
 * 🪤 게다가 500 이면 캐시가 안 채워져 <b>자가회복이 없고</b> 요청마다 ~1초를 태운다(실측 1.01s vs 25ms).
 *
 * @author sonix
 */
    Optional<TenantEntity> findByIdEquals(Long id);

    Optional<TenantEntity> findByTenantId(String tenantId);
    Optional<TenantEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
