package com.sonix.queue.api.security;

import lombok.Getter;

/**
 * 인증된 Tenant 정보. JWT·API-Key 두 경로가 공통으로 쓴다.
 *
 * <p><b>주의:</b> 두 경로가 확보하는 정보가 다르다. 아래 {@code tenantId} 참고.
 */
@Getter
public class TenantAuth {

    /** Tenant PK. <b>두 인증 경로 모두 항상 채운다</b> — 식별이 필요하면 이 값을 쓸 것. */
    private final Long id;

    /**
     * 도메인 ID ({@code t_xxx}). <b>API-Key 인증 경로에서는 null이다.</b>
     *
     * <p>{@code api_keys}는 PK만 들고 있어 이 값을 알려면 Tenant를 한 번 더 조회해야 한다.
     * 인증 핫패스에 조회를 더하지 않으려고 비워 둔다.
     *
     * <p>과거 Rate Limit이 이 값으로 Tenant를 조회하다가 항상 미스가 나서
     * <b>모든 요청을 통과시킨 적이 있다.</b> 이 필드를 쓰기 전에 두 경로 모두에서
     * 채워지는지 반드시 확인할 것.
     */
    private final String tenantId;

    public TenantAuth(final Long id, final String tenantId) {
        this.id = id;
        this.tenantId = tenantId;
    }
}
