package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
/**
 * ⚠️ <b>{@code tenants.plan} 컬럼은 DB에 남아 있지만 이 엔티티가 매핑하지 않는다</b>(§88 — 등급제 제거).
 * {@code NOT NULL}이고 DEFAULT가 있어 INSERT에 없어도 DB가 채우고, {@code ddl-auto: validate}는
 * 엔티티→테이블 방향만 검사하므로 매핑하지 않은 컬럼이 있어도 기동을 막지 않는다(실측).
 *
 * <p>컬럼을 남긴 이유: 요금제를 팔게 되면 컬럼이 있어야 ALTER 없이 매핑만 되살리면 된다.
 * <b>읽는 코드가 0인 상태를 의도적으로 허용한 §4-1의 명시적 예외</b>이며 같은 사실이
 * {@code doc/schema.sql} 컬럼 주석과 {@code doc/monitoring/}의 런북·쿼리집에도 적혀 있다.
 *
 * <p>🪤 <b>되살릴 때 DEFAULT를 확인하라 — 한 번 어긋난 적이 있다.</b> 이 컬럼은 원래
 * {@code ALTER TABLE ADD COLUMN ... DEFAULT 0}으로 추가돼 실물이 0이었는데, §88 전까지는 앱이
 * 항상 3을 명시적으로 INSERT해서 가려져 있었다. <b>매핑에서 빼면서 DEFAULT가 처음 하중을 받아
 * 드러났다.</b> 2026-09-04에 3으로 맞췄다(master·replica 실측 확인).
 *
 * <p>🪤 <b>필드를 지울 때는 그 위 애노테이션·javadoc이 다음 필드에 붙지 않는지 확인하라.</b>
 * 이 커밋에서 실제로 두 번 밟았다 — {@code @JdbcTypeCode(TINYINT)}가 {@code createdAt}에 붙어
 * 기동이 깨졌고(통합 테스트가 잡았다. 단위 레인은 초록이었다), 그걸 고치며 넣은 javadoc이
 * 또 {@code createdAt}에 붙었다(리뷰가 잡았다).
 */
public class TenantEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Long id;
    // schema.sql이 정의한 폭을 명시한다. 생략하면 Hibernate 기본값(255)이 적용되어
    // ddl-auto: update가 컬럼을 넓혀버리고, validate 환경에서는 기동이 막힌다.
    @Column(length = 50)
    String tenantId;
    String email;
    String passwordHash;
    @Column(length = 100)
    String name;
    /**
     * TINYINT 매핑.
     *
     * <p>schema.sql이 TINYINT로 정의한 컬럼이다(값 범위가 좁아 저장공간·비교 성능을 아끼려는
     * 의도적 선택). 자바 int/Integer는 기본적으로 INTEGER로 매핑되므로 명시하지 않으면
     * {@code ddl-auto: validate}가 타입 불일치로 기동을 거부하고, {@code update}는 반대로
     * 컬럼을 INT로 바꿔버려 그 의도를 조용히 되돌린다.
     */
    @JdbcTypeCode(SqlTypes.TINYINT)
    int status;

    LocalDateTime createdAt;


    protected TenantEntity() {}

    public Tenant toDomain() {
        return Tenant.reconstruct(this.id,this.tenantId, this.email
                , this.passwordHash, this.name
                , TenantStatus.fromCode(status)
                , this.createdAt
        );
    }

    public static TenantEntity fromDomain(Tenant tenant) {
        TenantEntity entity = new TenantEntity();
        entity.id = tenant.getId();
        entity.tenantId = tenant.getTenantId();
        entity.email = tenant.getEmail();
        entity.passwordHash = tenant.getPasswordHash();
        entity.name = tenant.getName();
        entity.status = tenant.getStatus().getStatusCode();
        entity.createdAt = tenant.getCreatedAt();

        return entity;
    }

}
