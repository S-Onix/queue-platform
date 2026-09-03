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
 * <p>알고리즘별 적합한 상황에 따라 두 Rate Limiter 사용:
 * <ul>
 *   <li>인증 후 (테넌트 단위): Token Bucket — burst 허용 (콘서트 티켓팅 등). 한도는 상수다(§88)</li>
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
 *       <li>인증 후 (TenantAuth 있음): Token Bucket + 테넌트 한도(상수)</li>
 *       <li>인증 전 (signup/login/refresh): Fixed Window + 고정 한도</li>
 *     </ul>
 *   </li>
 *   <li>해당 Limiter 호출 → 한도 초과면 429 응답 + {@code Retry-After} 헤더</li>
 * </ol>
 * */
@Component
@Log4j2
public class RateLimitFilter extends OncePerRequestFilter {

    /** 폴링 버킷 용량. 재접속·화면 복귀 시의 연속 요청을 흡수할 만큼만 둔다. */
    private static final int POLL_CAPACITY = 5;

    /**
     * 폴링 버킷 회복 속도.
     *
     * <p>0.5/s였을 때 pacing 최저 구간 2초와 소비 속도가 정확히 같아, 앞줄(rank≤50) 사용자는
     * 여유가 0이었다. 시계 오차나 재시도 한 번이 곧바로 429가 됐다.
     * 1.0/s면 2초 간격 폴링이 토큰 1개를 쓰고 2개를 회복해 버킷이 늘 차 있다.
     *
     * <p>⚠️ <b>이 한도는 pacing 최저 구간(2초)과 클라이언트 지터에 매여 있다</b> (§79).
     * 지터가 아래로도 흩어지면 실효 간격이 2초 밑으로 내려간다 — {@code /status}는 이 필터를
     * 지나가지 않으므로 여기서 소비되는 것은 개인 엔드포인트 호출뿐이고, 그래서 -20%(1.6초)까지는
     * 0.625/s로 여유가 남는다. 다만 <b>지터 규약 자체가 §79 안에서 갈린다</b>
     * ({@code QueueStatusResponse} 참조) — pacing 하한이나 지터 폭을 바꿀 때 이 값을 같이 봐라.
     */
    private static final double POLL_REFILL_PER_SEC = 1.0;

    /**
     * 테넌트 한도 — <b>모든 테넌트에 동일</b>. 예전 {@code Plan.ENTERPRISE}와 같은 값이라
     * 등급제를 걷어내도(§88) <b>동작이 바뀌지 않는다</b>(전 테넌트가 이미 ENTERPRISE였다).
     *
     * <p>🔴 <b>이 값은 지금 방어 역할을 못 한다 — 재조정 대상이다.</b> 100,000/분 = 지속
     * <b>1,667 rps</b>인데, FRS 실측의 플랫폼 수용량이 동시 오픈 8개 × 200 rps = <b>1,600 rps</b>다.
     * 즉 <b>테넌트 하나의 한도가 플랫폼 전체 실측 수용량과 같다.</b> 독식을 막으라고 있는 값이
     * 독식을 정확히 허용하는 크기다.
     *
     * <p>그래도 이번에 안 내린다 — <b>구조 변경(등급제 제거)과 값 재조정을 같은 커밋에 넣으면
     * 무엇이 원인인지 못 가린다.</b> 값은 k6로 재서 별도로 정한다. 내릴 때 함께 볼 것:
     * 이 버킷은 enqueue 전용이 아니라 <b>인증된 요청 전부</b>가 공유하므로(admit·complete 포함),
     * 낮추면 진행 중인 이벤트의 <b>입장까지</b> 함께 조인다.
     *
     * <p>비율 {@code capacity = refill × 60}은 §62에서 온 것이고 그 근거는 유지된다 —
     * 티켓팅은 오픈 1분 안에 몰리므로 1분치 burst를 허용한다.
     */
    static final int TENANT_CAPACITY = 100_000;
    static final double TENANT_REFILL_PER_SEC = 1_666.67;

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
     * <p>🔴 <b>{@code getRequestURI()}를 직접 쓰면 안 된다.</b> 그건 디코딩 전 원문이라
     * 디스패처·시큐리티가 보는 문자열과 다르다. 두 계층이 다른 문자열을 보면 한도가 통째로 사라진다
     * — 2026-08-28 실측으로 두 곳이 뚫렸다:
     * <ul>
     *   <li>{@code POST /api/v1/tenants/log%69n} → 15회 전부 401(한도 없음).
     *       평문은 11회째 429다. 본문이 {@code T003}이라 <b>자격 증명 비교가 실제로 수행됐다</b>
     *       = brute force에 한도가 없다</li>
     *   <li>폴링에서 {@code tokenId}의 한 글자만 인코딩하면 <b>버킷이 새로 생긴다</b>.
     *       평문 소진(429) 상태에서 {@code %74} 변형이 200을 5번 더 받았다. 변형은 글자 수만큼 있다</li>
     * </ul>
     *
     * <p>고치는 방향은 "인코딩을 막는다"가 아니라 <b>디스패처와 같은 문자열을 본다</b>이다.
     * 막는 쪽은 목록 관리가 되고, 목록은 언젠가 어긋난다. {@link UrlPathHelper}는 Spring MVC가
     * 쓰는 것과 같은 정규화(1회 디코딩 · 컨텍스트 경로 제거 · 경로 파라미터 {@code ;x=1} 제거)를 한다.
     *
     * <p>🔑 <b>"완전히 디코딩"이 목표가 아니다.</b> 이중 인코딩({@code %2569})은 1회 디코딩하면
     * {@code %69}로 남아 여기서도 안 맞지만, <b>디스패처에서도 안 맞아 404</b>가 된다.
     * 두 계층이 같은 규칙으로 어긋나므로 우회가 성립하지 않는다. 필요한 성질은 "같은 문자열"이지
     * "원본 복원"이 아니다.
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
        // 🔴 예전엔 아래 3)의 else 가지에서만 걸었다. 그러면 **클라이언트가 한도를 고를 수 있다** —
        //    /login은 permitAll이라 남의(또는 자기) Access 토큰을 Authorization 헤더에 붙여도 그대로
        //    통과하고, JwtAuthenticationFilter가 컨텍스트를 채운 뒤라 3)이 "인증된 요청"으로 분기한다.
        //    결과: brute force가 LOGIN(10/분/IP)이 아니라 **공격자 자신의 테넌트 버킷**을
        //    소비한다. 계정을 K개 만들면 버킷도 K개라 IP 기준 한도가 사실상 사라진다.
        //
        //    로그인 시도의 신원은 **body의 email**이지 헤더의 토큰이 아니다. 그러니 이 세 경로에서는
        //    헤더를 보고 한도를 고르면 안 된다. 위 폴링 선처리와 같은 모양이다.
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
     * <p><b>한도는 모든 테넌트에 동일한 상수다.</b> 예전엔 {@code Plan} enum이 등급별로 값을
     * 정했는데(§62), 그 등급제를 걷어냈다(§88). 근거는 아래 두 줄이다 —
     * <b>과금은 plan을 읽지 않고</b>({@code billing_snapshots}에 컬럼이 없다. 청구는 token 개수다),
     * plan을 읽는 코드가 <b>여기 하나뿐</b>이었다. 즉 등급제가 실제로 하던 일은
     * "SaaS 약속"이 아니라 <b>테넌트 하나가 플랫폼을 독식하지 못하게 막는 방어</b> 하나였고,
     * <b>방어는 등급이 아니라 상수여야 한다</b> — 차등을 두는 순간 그건 방어가 아니라 판매다.
     * §87이 {@code maxCapacity} 상한을 "플랜별"이 아니라 상수로 둔 것과 같은 판단이다.
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
