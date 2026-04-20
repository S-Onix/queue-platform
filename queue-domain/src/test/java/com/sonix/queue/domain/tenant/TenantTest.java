package com.sonix.queue.domain.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TenantTest {

    @Test
    @DisplayName("생성시 status = ACTIVE")
    void create_status_is_active(){
        String email = "test@email.com";
        String name = "testName";
        String passwordHash = "hashed1234";

        Tenant tenant = Tenant.create(email, passwordHash, name);

        assertEquals(TenantStatus.ACTIVE, tenant.getStatus());
    }

    @Test
    @DisplayName("생성시 tenantId는 t_로 시작")
    void create_tenantId_starts_with_t(){
        Tenant tenant = Tenant.create("test@email.com","passwordHash","testName");
        assertTrue(tenant.getTenantId().startsWith("t_"));
    }

    @Test
    @DisplayName("비활성화 성공")
    void deactivate_success() {
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");

        tenant.deactivate();

        assertEquals(TenantStatus.DEACTIVATED, tenant.getStatus());
    }

    @Test
    @DisplayName("이미 비활성화된 Tenant 비활성화 시 예외")
    void deactivate_already_deactivated_throws() {
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");
        tenant.deactivate();  // 한 번 비활성화

        assertThrows(IllegalStateException.class, () -> {  // 어떤 예외가 나와야 할까?
            tenant.deactivate();  // 두 번째 시도
        });
    }

    @Test
    @DisplayName("비밀번호 변경 성공")
    void changePassword_success() {
        Tenant tenant = Tenant.create("test@email.com", "oldHash", "테스트");

        tenant.changePassword("newHash");

        assertEquals("newHash", tenant.getPasswordHash());
    }

    @Test
    @DisplayName("비밀번호 null이면 예외")
    void changePassword_null_throws() {
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");

        assertThrows(IllegalArgumentException.class, () -> {
            tenant.changePassword(null);
        });
    }

    @Test
    @DisplayName("isActive - ACTIVE면 true")
    void isActive_when_active() {
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");

        assertTrue(tenant.isActive()); // true
    }

    @Test
    @DisplayName("isActive - DEACTIVATED면 false")
    void isActive_when_deactivated() {
        Tenant tenant = Tenant.create("test@email.com", "hash", "테스트");
        tenant.deactivate();

        assertFalse(tenant.isActive()); // false
    }
}
