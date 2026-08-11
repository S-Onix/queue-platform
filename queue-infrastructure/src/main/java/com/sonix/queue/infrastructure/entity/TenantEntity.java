package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.tenant.Plan;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "tenants")
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
    @JdbcTypeCode(SqlTypes.TINYINT)
    int plan;
    LocalDateTime createdAt;


    protected TenantEntity() {}

    public Tenant toDomain() {
        return Tenant.reconstruct(this.id,this.tenantId, this.email
                , this.passwordHash, this.name
                , TenantStatus.fromCode(status), Plan.fromCode(plan)
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
        entity.plan = tenant.getPlan().getCode();
        entity.createdAt = tenant.getCreatedAt();

        return entity;
    }

}
