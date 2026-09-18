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
 * 이유: tenants 매핑. ⚠️ <b>{@code plan} 컬럼은 DB 에 남아 있지만 이 엔티티가 매핑하지 않는다</b>(§88).
 * 원인: {@code ddl-auto: validate} 는 엔티티→테이블 방향만 봐서 <b>매핑 안 한 컬럼은 기동을 막지 않는다</b>.
 * 해결: 컬럼은 남긴다(팔게 되면 매핑만 되살린다) — <b>§4-1 의 명시적 예외</b>다.
 * 🪤 되살릴 때 <b>DEFAULT 를 확인하라</b> — 실물이 0 이었는데 앱이 항상 3 을 INSERT 해 가려져 있었고,
 *    매핑에서 빼면서 <b>DEFAULT 가 처음 하중을 받아 드러났다</b>(2026-09-04 에 3 으로 맞췄다).
 * 🪤 필드를 지울 때 <b>애노테이션·javadoc 이 다음 필드에 붙지 않는지</b> 확인하라(두 번 밟았다).
 *
 * @author sonix
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
