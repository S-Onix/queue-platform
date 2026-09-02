package com.sonix.queue.infrastructure.repository;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.infrastructure.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, Long> {

    /**
     * PK 조회. 🔴 <b>상속받은 {@code findById}를 쓰지 마라 — 그건 replica로 간다.</b>
     *
     * <p>{@code SimpleJpaRepository}가 직접 구현한 CRUD 메서드에는 그 클래스의
     * {@code @Transactional(readOnly = true)}가 걸려 있어 {@code ReplicationRoutingDataSource}가
     * <b>replica로 보낸다</b>(실측). 인터페이스에 선언한 파생 쿼리는 트랜잭션이 열리지 않아
     * <b>master</b>로 간다 — 판정 기준은 "readOnly 트랜잭션이 열렸는가" 하나다(§4-3).
     *
     * <p><b>왜 master여야 하나 (실측 2026-09-01):</b> 이 조회는 {@code RateLimitFilter}가
     * <b>모든 인증 요청</b>마다 타는 경로다(테넌트 캐시 미스 시). replica를 향하면 replica가
     * 죽는 순간 <b>인증된 API 전체가 500</b>이 된다 — enqueue 핫패스 포함. 게다가 500이 나면
     * 캐시가 채워지지 않아 <b>자가회복이 없고</b>, 요청마다 커넥션 시도로 <b>~1초</b>를 쓴다.
     * 실측: replica를 죽인 인스턴스에서 6회 연속 500(각 1.01~1.02초), 정상 인스턴스는 200(~25ms).
     *
     * <p>즉 <b>읽을 것이 없어 DR로만 남은 replica가, 인증 경로를 자기 가용성에 묶고 있었다.</b>
     * 이 서비스의 DB 읽기는 구조적으로 거의 전부 쓰기 직후라 replica로 보낼 읽기가 원래 없다
     * ({@code doc/reviews/2026-09-01-replica-assumption-audit.md}).
     */
    Optional<TenantEntity> findByIdEquals(Long id);

    Optional<TenantEntity> findByTenantId(String tenantId);
    Optional<TenantEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
