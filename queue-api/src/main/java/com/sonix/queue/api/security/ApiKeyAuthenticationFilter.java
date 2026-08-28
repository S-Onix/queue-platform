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
     * <b>Tenant가 X-API-Key로 부르는 경로에만</b> 이 필터를 적용한다.
     *
     * <p>화이트리스트라 <b>새 엔드포인트를 추가하고 여기를 잊으면 조용히 401</b>이 된다.
     * 실제로 그렇게 됐다 — §80이 admit·verify·complete 컨트롤러를 추가했으나 이 조건이
     * enqueue 하나에 머물러, FRS가 X-API-Key로 명세한 세 경로가 <b>전부 401</b>이었다.
     * (JWT Bearer로는 통과해서 테스트가 잡지 못했다.) 컨트롤러의 {@code @*Mapping}을
     * 전수로 세어 대조할 것.
     *
     * <p>🪤 <b>인증 주체가 경로마다 다르다.</b> 아래 둘은 이 필터를 <b>타지 않아야</b> 한다.
     * <ul>
     *   <li>{@code GET /{queueId}/tokens/{tokenId}} — 폴링. <b>유저가 직접</b> 부르고 API Key가 없다</li>
     *   <li>{@code GET /{queueId}/status} — 공용 전광판. {@code permitAll}이다</li>
     * </ul>
     * 그래서 {@code /tokens/{tokenId}/complete}와 {@code /tokens/{tokenId}}를 구분해야 한다 —
     * 정규식이 뭉개지면 <b>폴링이 401</b>이 된다.
     *
     * <p>🔴 <b>정규식에 넣는 문자열로 {@code getRequestURI()}(원문)를 쓰면 안 된다.</b> {@code RateLimitFilter}가 같은 이유로
     * 뚫렸다 — 원문으로 판정하면 디스패처가 보는 문자열과 갈린다.
     *
     * <p>여기는 갈려도 <b>fail-closed</b>라 우회가 아니라 오작동이었다(실측 2026-08-28):
     * {@code POST /api/v1/queues/q_x/tokens} → 404 Q001(정상 도달)인데
     * {@code POST /api/v1/queues/q_x/token%73} → <b>401</b>. 경로를 인코딩하는 Tenant 클라이언트는
     * enqueue·admit·verify·complete가 통째로 401이 된다.
     *
     * <p>⚠️ 더 중요한 건 <b>방향이 뒤집힐 수 있다는 것</b>이다. 위 4경로 중 하나라도 permitAll이
     * 되는 순간 fail-closed가 fail-open이 된다. 두 필터가 같은 문자열을 보게 두는 것이 유일한 방어다.
     *
     * <p>🔑 {@code setDefaultEncoding("UTF-8")}: {@code UrlPathHelper}는 기본적으로
     * {@code request.getCharacterEncoding()}으로 디코딩하는데 GET·JSON POST에서는 그게 {@code null}이라
     * ISO-8859-1로 떨어진다. Tomcat·{@code PathPatternParser}는 UTF-8이다. 지금 쓰는 경로는
     * 전부 ASCII(UUID hex)라 도달 불가지만, 두 계층의 규칙을 굳이 다르게 둘 이유가 없다.
     * ⚠️ 다만 이것으로 완전히 같아지지는 않는다 — {@code setDefaultEncoding}은
     * {@code getCharacterEncoding()}이 <b>null일 때만</b> 덮으므로, 클라이언트가
     * {@code Content-Type: ...;charset=EUC-KR}을 보내면 경로가 그 charset으로 디코딩된다.
     * 지금 매칭 대상 세그먼트가 전부 ASCII라 세 charset에서 결과가 같아 도달 불가이나
     * <b>미측정이다.</b>
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
