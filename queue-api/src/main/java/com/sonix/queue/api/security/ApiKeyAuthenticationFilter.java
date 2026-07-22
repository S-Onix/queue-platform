package com.sonix.queue.api.security;

import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyCache;
import com.sonix.queue.domain.apikey.ApiKeyHasher;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * X-API-Key 기반 인증 필터.
 *
 * <p>Tenant 서버가 대기열 엔진 API(enqueue 등)를 호출할 때 사용하는 X-API-Key를
 * 검증하고, 인증 성공 시 {@link TenantAuth}를 SecurityContext에 주입한다.
 *
 * <p>JWT 인증({@link JwtAuthenticationFilter})과 대칭 구조이며,
 * 엔진 계열 경로에서만 동작한다(shouldNotFilter로 대상 제한).
 *
 * <p><b>검증 흐름:</b> 헤더 추출 → SHA-256 해싱 → 캐시 조회(양성/음성) →
 * 캐시 미스 시 DB 조회 후 캐시 반영 → ACTIVE 확인 → tenantId를 SecurityContext에 주입.
 * 실패 시 컨텍스트를 비우고 다음 필터로 진행하여 SecurityConfig가 최종 401을 처리한다.
 */
@Component
@Log4j2
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyCache apiKeyCache;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, ApiKeyCache apiKeyCache) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyCache = apiKeyCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String rawKey = request.getHeader(API_KEY_HEADER);

        if (rawKey != null && !rawKey.isBlank()) {
            try {
                String keyHash = ApiKeyHasher.hash(rawKey);
                Optional<ApiKey> found = lookup(keyHash);

                if (found.isPresent() && found.get().isActive()) {
                    ApiKey apiKey = found.get();
                    // ApiKey는 tenantId(Long PK)만 보유 → TenantAuth.id에 주입,
                    // String tenantId는 API-Key 경로에서 불필요하므로 null
                    TenantAuth tenantAuth = new TenantAuth(apiKey.getTenantId(), null);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(tenantAuth, null, List.of());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    log.debug("API Key authentication failed: not found or inactive");
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.debug("API Key authentication error: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 캐시 우선 조회 후 미스 시 DB fallback.
     * DB 조회 결과를 캐시에 반영한다(존재하면 put, 없으면 putNegative).
     */
    private Optional<ApiKey> lookup(String keyHash) {
        Optional<ApiKey> cached = apiKeyCache.get(keyHash);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<ApiKey> fromDb = apiKeyRepository.findByKeyHash(keyHash);
        if (fromDb.isPresent()) {
            apiKeyCache.put(fromDb.get());
        } else {
            apiKeyCache.putNegative(keyHash);  // 캐시 관통(penetration) 방지
        }
        return fromDb;
    }

    /**
     * 엔진 계열 경로에서만 이 필터를 적용한다.
     * enqueue: POST /api/v1/queues/{queueId}/tokens
     * (이후 admit/verify/complete 추가 시 이 조건을 확장)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // /api/v1/queues/{queueId}/tokens 형태만 통과
        boolean isEnqueuePath = uri.matches("/api/v1/queues/[^/]+/tokens");
        return !isEnqueuePath;
    }
}
