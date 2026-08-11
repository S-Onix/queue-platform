package com.sonix.queue.api.security;

import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.ratelimit.FixedWindowRateLimiter;
import com.sonix.queue.domain.ratelimit.RateLimiter;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantCache;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.ratelimit.RateLimitKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Rate Limit Filter.
 *
 * <p>알고리즘별 적합한 상황에 따라 두 Rate Limiter 사용:
 * <ul>
 *   <li>인증 후 (Tenant Plan SLA): Token Bucket — burst 허용 (콘서트 티켓팅 등)</li>
 *   <li>인증 전 (보안): Fixed Window — burst 불허 (Brute Force 방지)</li>
 * </ul>
 *
 * *** 반드시 JwtAuthenticationFilter 실행 이후 RateLimitFilter가 실행되야함
 *
 * <p>요청 흐름:
 * <ol>
 *   <li>{@link JwtAuthenticationFilter}가 이미 SecurityContext에 TenantAuth 저장</li>
 *   <li>이 Filter가 인증 여부 확인 후 키 + 알고리즘 결정
 *     <ul>
 *       <li>인증 후 (TenantAuth 있음): Token Bucket + Tenant Plan 한도</li>
 *       <li>인증 전 (signup/login/refresh): Fixed Window + 고정 한도</li>
 *     </ul>
 *   </li>
 *   <li>해당 Limiter 호출 → 한도 초과면 429 응답 + {@code Retry-After} 헤더</li>
 * </ol>
 * */
@Component
@Log4j2
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter tokenBucketRateLimiter;
    private final FixedWindowRateLimiter fixedWindowRateLimiter;
    private final TenantRepository tenantRepository;
    private final TenantCache tenantCache;

    public RateLimitFilter(
            RateLimiter tokenBucketRateLimiter,
            FixedWindowRateLimiter fixedWindowRateLimiter,
            TenantRepository tenantRepository,
            TenantCache tenantCache
    ){
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
        this.fixedWindowRateLimiter = fixedWindowRateLimiter;
        this.tenantRepository = tenantRepository;
        this.tenantCache = tenantCache;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 1) Actuator 등은 Rate Limit 적용 제외
        if (shouldSkip(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2) 인증 여부 확인
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof TenantAuth tenantAuth) {
            // 인증된 요청 → Token Bucket + Tenant Plan
            if (!checkAuthenticatedRateLimit(tenantAuth, response)) {
                return;  // 429 응답으로 종료
            }
        } else {
            // 인증 전 요청 → Fixed Window + IP 기반
            if (!checkPublicRateLimit(request, response)) {
                return;  // 429 응답으로 종료
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Rate Limit 적용 제외 endpoint.
     */
    private boolean shouldSkip(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }

    /**
     * 인증된 요청 — Token Bucket으로 Tenant Plan 한도 체크.
     * @return true=통과, false=거부 (429 응답 완료)
     */
    private boolean checkAuthenticatedRateLimit(
            TenantAuth tenantAuth, HttpServletResponse response) throws IOException {

        // PK로 조회한다. TenantAuth.tenantId(String)는 API-Key 인증 경로에서 null이라
        // 그것으로 조회하면 항상 미스가 나고, 아래 분기가 모든 요청을 통과시켜
        // Rate Limit이 사실상 꺼진 상태가 된다.
        Optional<Tenant> tenantOpt = loadTenant(tenantAuth.getId());

        if (tenantOpt.isEmpty()) {
            // 인증은 됐는데 Tenant가 없다 = 데이터 정합성 문제. 요청을 막지는 않되 드러나게 남긴다.
            log.warn("Tenant not found for rate limit: id={}", tenantAuth.getId());
            return true;  // 통과 (인증 실패는 다른 Filter가 처리)
        }

        Tenant tenant = tenantOpt.get();
        String key = RateLimitKeys.tenant(tenant.getTenantId());
        int capacity = tenant.getPlan().getCapacity();
        double refillRate = tenant.getPlan().getRefillRatePerSecond();

        boolean allowed = tokenBucketRateLimiter.tryAcquire(key, capacity, refillRate);

        if (!allowed) {
            log.debug("Token Bucket rate limit exceeded: key={}, plan={}",
                    key, tenant.getPlan());
            // Retry-After: refillRate 기반 (1 토큰 회복 시간)
            long retryAfter = Math.max(1, (long) Math.ceil(1.0 / refillRate));
            writeTooManyRequests(response, retryAfter);
            return false;
        }

        return true;
    }

    /**
     * 인증 전 요청 — Fixed Window로 IP 기반 한도 체크.
     * @return true=통과, false=거부 (429 응답 완료)
     */
    private boolean checkPublicRateLimit(
            HttpServletRequest request, HttpServletResponse response) throws IOException {

        String path = request.getRequestURI();
        PublicEndpointRateLimit publicLimit = resolvePublicEndpoint(path);

        if (publicLimit == null) {
            // 인증 필요 endpoint를 인증 없이 호출 → SecurityConfig가 401 처리
            return true;
        }

        String ip = extractIp(request);
        String action = resolveActionName(path);
        String key = RateLimitKeys.publicEndPoint(action, ip);


        boolean allowed = fixedWindowRateLimiter.tryAcquire(
                key,
                publicLimit.getLimit(),
                publicLimit.getWindowSizeMillis()
        );

        if (!allowed) {
            log.debug("Fixed Window rate limit exceeded: key={}, limit={}/window",
                    key, publicLimit.getLimit());
            // Retry-After: 윈도우 크기 (보수적)
            long retryAfter = publicLimit.getWindowSizeMillis() / 1000;
            writeTooManyRequests(response, retryAfter);
            return false;
        }

        return true;
    }

    /**
     * Cache Aside 패턴
     * 캐시에 있으면 캐시 없으면 DB 조회
     * */
    /**
     * Tenant 조회 (Cache Aside).
     *
     * <p>PK로 조회하는 이유: JWT와 API-Key 두 인증 경로가 공통으로 확보하는 식별자가 PK뿐이다.
     * API-Key는 {@code api_keys.tenant_id}(PK)만 들고 있어 {@code t_xxx} 형태를 모른다.
     */
    private Optional<Tenant> loadTenant(Long id) {
        return tenantCache.get(id)
                .or(() -> {
                    Optional<Tenant> dbResult = tenantRepository.findById(id);
                    dbResult.ifPresent(tenantCache::put);
                    return dbResult;
                });
    }

    private PublicEndpointRateLimit resolvePublicEndpoint(String path) {
        if (path.equals("/api/v1/tenants/signup")) return PublicEndpointRateLimit.SIGNUP;
        if (path.equals("/api/v1/tenants/login")) return PublicEndpointRateLimit.LOGIN;
        if (path.equals("/api/v1/tenants/refresh")) return PublicEndpointRateLimit.REFRESH;
        return null;
    }

    private String resolveActionName(String path) {
        if (path.equals("/api/v1/tenants/signup")) return "signup";
        if (path.equals("/api/v1/tenants/login")) return "login";
        if (path.equals("/api/v1/tenants/refresh")) return "refresh";
        return "unknown";
    }

    /**
     * 실제 클라이언트 IP 추출.
     * X-Forwarded-For 헤더 우선 (Nginx 등 프록시 거친 경우).
     */
    private String extractIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 429 Too Many Requests 응답.
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        response.getWriter().write(String.format(
                "{\"error\":\"%s\",\"message\":\"%s\",\"retryAfter\":%d}",
                ErrorCode.RL_001_KEY_LIMIT.getCode(),
                ErrorCode.RL_001_KEY_LIMIT.getMessage(),
                retryAfterSeconds
        ));
    }
}
