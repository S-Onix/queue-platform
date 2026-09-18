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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 이유: X-API-Key 기반 인증 필터. Tenant 서버가 엔진 API 를 부를 때 쓰는 키를 검증한다.
 * 해결: 헤더 추출 → SHA-256 → 캐시(양성·음성) → 미스면 DB → ACTIVE 확인 →
 *       {@link TenantAuth} 를 SecurityContext 에 주입한다.
 * 🔑 {@link JwtAuthenticationFilter} 와 대칭이고 <b>엔진 계열 경로에서만</b> 돈다.
 *
 * @author sonix
 */
@Component
@Log4j2
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyCache apiKeyCache;

    /** 경로 판정용 정규화. 원문을 쓰면 안 되는 이유는 {@link #shouldNotFilter} javadoc 참조. */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    static { PATH_HELPER.setDefaultEncoding("UTF-8"); }

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
     * 이유: <b>Tenant 가 X-API-Key 로 부르는 경로에만</b> 필터를 적용한다(화이트리스트).
     * 🔴 <b>새 엔드포인트를 추가하고 여기를 잊으면 401 이다</b> — §80 의 세 경로가 전부 그랬고
     *    JWT 로는 통과해 테스트가 못 잡았다. 컨트롤러 매핑과 <b>전수로 대조</b>할 것.
     * 🪤 폴링·{@code /status} 는 제외다 — 두 경로를 가르는 정규식이 뭉개지면 <b>폴링이 401</b> 이 된다.
     * 🔴 정규식에 {@code getRequestURI()}(원문)를 쓰지 마라 — 정규화된 경로를 써야 fail-closed 가 유지된다.
     *
     * @author sonix
     */
   @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = PATH_HELPER.getPathWithinApplication(request);   // 원문 금지 — 위 javadoc
        boolean isTenantEnginePath =
                   uri.matches("/api/v1/queues/[^/]+/tokens")                       // enqueue
                || uri.matches("/api/v1/queues/[^/]+/admit")                        // admit
                || uri.matches("/api/v1/queues/[^/]+/admit-tokens/[^/]+/verify")    // verify
                || uri.matches("/api/v1/queues/[^/]+/tokens/[^/]+/complete");       // complete
        return !isTenantEnginePath;
    }
}
