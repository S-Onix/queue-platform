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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * Rate Limit Filter.
 *
 * <p>알고리즘을 상황별로 나눈다 (§60·§61).
 * <ul>
 *   <li>인증 후(테넌트 단위): Token Bucket — burst 허용(티켓팅). 한도는 전 테넌트 상수(§88)</li>
 *   <li>인증 전(signup/login/refresh): Fixed Window + IP — burst 불허(brute force 방지)</li>
 *   <li>폴링: Token Bucket + tokenId. 인증이 없어 <b>선처리</b>한다</li>
 * </ul>
 *
 * <p>🔴 <b>반드시 {@link JwtAuthenticationFilter} 뒤에 실행돼야 한다</b> — 인증 여부로 키와
 * 알고리즘을 고르기 때문이다. 한도 초과는 429 + {@code Retry-After}로 여기서 끝낸다.
 */
@Component
@Log4j2
public class RateLimitFilter extends OncePerRequestFilter {

    /** 폴링 버킷 용량. 재접속·화면 복귀 시의 연속 요청을 흡수할 만큼만 둔다. */
    private static final int POLL_CAPACITY = 5;

    /**
     * 폴링 버킷 회복 속도.
     *
     * <p>0.5/s는 pacing 최저 구간(2초)과 소비 속도가 정확히 같아 앞줄 사용자의 여유가 0이었다 —
     * 시계 오차나 재시도 한 번이 곧바로 429였다. 1.0/s면 2초 간격이 1개를 쓰고 2개를 회복한다.
     *
     * <p>⚠️ <b>이 값은 pacing 하한(2초)과 지터 폭에 매여 있다</b>(§79). 둘 중 하나를 바꾸면
     * 여기도 같이 봐라. 현재 여유는 지터 -20%(1.6초)까지다.
     */
    private static final double POLL_REFILL_PER_SEC = 1.0;

    /**
     * 테넌트 한도 — <b>모든 테넌트에 동일</b>. 예전 {@code Plan.ENTERPRISE}와 같은 값이라
     * 등급제를 걷어내도(§88) <b>동작이 바뀌지 않는다</b>(전 테넌트가 이미 ENTERPRISE였다).
     *
     * <p><b>100,000 → 50,000 (§89).</b> 근거는 실측 하나다 — 100,000에서는 <b>리미터가 한 건도
     * 막지 않았다</b>(3,000 rps × 30초 = 90,000건이 capacity 안에서 끝나 429가 0건). 50,000이면
     * 같은 공격이 23.1초에 개입해 15,000건을 막는다.
     *
     * <p>🔑 <b>버스트 비용과 지속 비용이 다르다.</b> 몰리는 순간 버킷을 먹는 것은 enqueue뿐이라
     * "동시 N명 통과"는 {@code capacity}가 정한다(5만 명). 지속 소비는 유저 1명당 <b>3.01</b>
     * (enqueue 1 + verify 1 + complete 1 + admit 1/20, 실측)이라 833.34÷3.01 = <b>초당 277명</b>,
     * FRS 목표 부하를 한 테넌트가 혼자 다 써도 38% 여유가 남는다.
     *
     * <p>🪤 <b>enqueue 전용 버킷이 아니다</b> — 인증된 요청 전부가 공유하므로(admit·complete 포함)
     * 값을 내리면 진행 중인 이벤트의 <b>입장까지</b> 조인다.
     *
     * <p>🪤 <b>테넌트 단위라 큐를 나눠도 늘지 않는다</b>({@code rl:tenant:&#123;id&#125;}에 queueId가
     * 없다). 큐마다 버킷을 주는 안은 기각했다 — <b>§87과 같은 이유로 개수로 우회된다.</b>
     *
     * <p>비율 {@code capacity = refill × 60}은 §62에서 왔고 근거는 유지된다 — 티켓팅은 오픈
     * 1분 안에 몰리므로 1분치 burst를 허용한다.
     *
     * <p>🪤 <b>refill이 833.33이 아니라 833.34인 이유.</b> 833.33이면 {@code capacity/refill}이
     * 60.0002가 되어 {@code token-bucket.lua}의 {@code ceil()}이 <b>61</b>로 올라가고 버킷 TTL이
     * 120초 → 121초로 어긋난다. 비율 단정({@code within(1)})은 둘 다 통과하므로
     * <b>테스트가 이 어긋남을 안 잡는다</b>. 나눗셈이 정확히 60 이하로 떨어지는 값을 써야 한다.
     */
    static final int TENANT_CAPACITY = 50_000;
    static final double TENANT_REFILL_PER_SEC = 833.34;

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


    /**
     * 경로 판정에 쓰는 <b>유일한</b> 문자열원.
     *
     * <p>🔴 <b>{@code getRequestURI()}를 쓰면 안 된다.</b> 디코딩 전 원문이라 디스패처가 보는
     * 문자열과 다르고, 두 계층이 다른 문자열을 보면 한도가 통째로 사라진다. 2026-08-28에 두 곳이
     * 실제로 뚫렸다 — {@code /tenants/log%69n}이 15회 전부 한도 없이 자격 증명 비교까지 갔고,
     * 폴링은 {@code tokenId}를 한 글자 인코딩할 때마다 <b>버킷이 새로 생겼다</b>.
     *
     * <p>🔑 <b>목표는 "완전한 디코딩"이 아니라 "같은 문자열"이다.</b> {@link UrlPathHelper}는
     * Spring MVC와 같은 정규화(1회 디코딩 · 컨텍스트 경로 제거 · {@code ;x=1} 제거)를 한다.
     * 이중 인코딩({@code %2569})은 여기서도 안 맞지만 <b>디스패처에서도 안 맞아 404</b>라
     * 우회가 성립하지 않는다. 인코딩을 목록으로 막는 방향은 목록이 언젠가 어긋난다.
     */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    static {
        // 기본값은 request.getCharacterEncoding()인데 GET·JSON POST에선 null이라 ISO-8859-1로 떨어진다.
        // Tomcat·PathPatternParser는 UTF-8이다. 지금 경로는 전부 ASCII라 도달 불가지만
        // (공개 경로 3개 · tokenId는 UUID hex), 두 계층의 디코딩 규칙을 다르게 둘 이유가 없다.
        PATH_HELPER.setDefaultEncoding("UTF-8");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 경로는 여기서 한 번만 만든다. 아래 판정들이 전부 이 값을 쓴다(위 PATH_HELPER 주석 참조).
        String path = PATH_HELPER.getPathWithinApplication(request);

        // 1) Actuator 등은 Rate Limit 적용 제외
        if (shouldSkip(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        //Polling의 경우 API-KEY로 인증하지 않기 떄문에 선처리하여 확인한다.
        if (isPollPath(request.getMethod(), path)) {
            if (!checkPollRateLimit(path, response)) {
                return;   // 429로 종료
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 2) 공개 endpoint(signup/login/refresh)는 인증 여부와 무관하게 IP Fixed Window를 먼저 태운다.
        //
        // 🔴 3)의 else 가지에 맡기면 **클라이언트가 한도를 고를 수 있다** — /login은 permitAll이라
        //    Authorization 헤더를 붙이면 3)이 "인증된 요청"으로 분기하고, brute force가
        //    LOGIN(10/분/IP)이 아니라 공격자 자신의 테넌트 버킷을 먹는다(계정 K개 = 버킷 K개).
        //    로그인 시도의 신원은 **body의 email**이지 헤더의 토큰이 아니다.
        if (resolvePublicEndpoint(path) != null) {
            if (!checkPublicRateLimit(request, path, response)) {
                return;   // 429로 종료
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 3) 인증 여부 확인
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof TenantAuth tenantAuth) {
            // 인증된 요청 → Token Bucket (테넌트 단위, 한도는 상수)
            if (!checkAuthenticatedRateLimit(tenantAuth, response)) {
                return;  // 429 응답으로 종료
            }
        } else {
            // 인증 전 요청 → Fixed Window + IP 기반
            if (!checkPublicRateLimit(request, path, response)) {
                return;  // 429 응답으로 종료
            }
        }

        filterChain.doFilter(request, response);
    }

    /** GET /api/v1/queues/{queueId}/tokens/{tokenId} 형태만 poll로 인식. path는 정규화된 값이어야 한다. */
    private boolean isPollPath(String method, String path) {
        return "GET".equals(method)
                && path.matches("/api/v1/queues/[^/]+/tokens/[^/]+");
    }

    /**
     * tokenId 기준 Token Bucket.
     *
     * <p>⚠️ 버킷 키가 되는 tokenId는 <b>정규화된 경로</b>에서 뽑아야 한다. 원문에서 뽑으면
     * 인코딩 변형마다 다른 키가 나와 버킷이 무한정 새로 생긴다(위 PATH_HELPER 주석의 실측 참조).
     *
     * @return true=통과, false=거부(429 완료).
     */
    private boolean checkPollRateLimit(String path, HttpServletResponse res)
            throws IOException {
        String tokenId = path.substring(path.lastIndexOf('/') + 1);   // 마지막 세그먼트
        String key = RateLimitKeys.pollToken(tokenId);

        boolean allowed = tokenBucketRateLimiter.tryAcquire(key, POLL_CAPACITY, POLL_REFILL_PER_SEC);
        if (!allowed) {
            writeTooManyRequests(res, 2);   // 기존 429 응답기 재사용, Retry-After 2s
            return false;
        }
        return true;
    }

    /**
     * Rate Limit 적용 제외 endpoint.
     */
    private boolean shouldSkip(String path) {
        return path.startsWith("/actuator/");
    }

    /**
     * 인증된 요청 — Token Bucket으로 테넌트 한도 체크.
     *
     * <p><b>한도는 전 테넌트 동일한 상수다</b>(§88, 등급제 철회). 과금은 plan을 읽지 않고
     * (청구는 token 개수다) plan을 읽는 코드가 여기 하나뿐이었다 — 등급제가 실제로 하던 일은
     * <b>한 테넌트의 독식 방어</b> 하나였고, <b>방어는 등급이 아니라 상수여야 한다.</b>
     *
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

        boolean allowed = tokenBucketRateLimiter.tryAcquire(key, TENANT_CAPACITY, TENANT_REFILL_PER_SEC);

        if (!allowed) {
            log.debug("Token Bucket rate limit exceeded: key={}", key);
            // Retry-After: refill 기반 (1 토큰 회복 시간)
            long retryAfter = Math.max(1, (long) Math.ceil(1.0 / TENANT_REFILL_PER_SEC));
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
            HttpServletRequest request, String path, HttpServletResponse response) throws IOException {

        PublicEndpointRateLimit publicLimit = resolvePublicEndpoint(path);

        if (publicLimit == null) {
            // 인증 필요 endpoint를 인증 없이 호출 → SecurityConfig가 401 처리
            return true;
        }

        // 프록시가 없으므로 TCP peer가 유일한 사실. XFF는 클라이언트가 쓰는 값이라 신뢰 근거가 없다.
        // LB 도입 시 server.forward-headers-strategy=native + internal-proxies로 처리한다(앱 코드 아님).
        String ip = request.getRemoteAddr();
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
     * Tenant 조회 (Cache Aside — 캐시 미스면 DB 조회 후 적재).
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
