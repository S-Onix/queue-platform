package com.sonix.queue.domain.tenant;

import com.sonix.queue.common.util.IdGenerator;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Tenant {
    private Long id;
    private String tenantId;
    private String email;
    private String passwordHash;
    private String name;
    private TenantStatus status;
    private LocalDateTime createdAt;

    /**
     * 외부에서 생성하지 못하게 private으로 통제함
     * */
    private Tenant(){

    }

    private Tenant(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.status = TenantStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.tenantId = IdGenerator.generate("t_");
    }

    /**
     * 필수로 입력해야하는 것만 파라미터로 받아옴 (객체의 생성 통제권을 위해서 별도로 분리)
     * */
    public static Tenant create(String email, String passwordHash, String name){
        return new Tenant(email, passwordHash, name);
    }

    /**
     * DB에서 읽어온 데이터 복원
     * */
    public static Tenant reconstruct(Long id, String tenantId, String email,
                                     String passwordHash, String name,
                                     TenantStatus status, LocalDateTime createdAt) {
        Tenant tenant = new Tenant();
        tenant.id = id;
        tenant.tenantId = tenantId;
        tenant.email = email;
        tenant.passwordHash = passwordHash;
        tenant.name = name;
        tenant.status = status;
        tenant.createdAt = createdAt;
        return tenant;
    }

    public boolean isActive(){
        return this.status != TenantStatus.DEACTIVATED;
    }

    public void changePassword(String passwordHash){
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호는 필수값입니다.");
        }
        this.passwordHash = passwordHash;
    }

    public void deactivate(){
        if(this.status == TenantStatus.DEACTIVATED)
            throw new IllegalStateException("이미 비활성화된 Tenant 입니다.");
        this.status = TenantStatus.DEACTIVATED;
    }

}
