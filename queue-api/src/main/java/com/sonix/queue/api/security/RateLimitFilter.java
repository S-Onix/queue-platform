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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * 이유: Rate Limit 필터. <b>알고리즘을 상황별로 나눈다</b>(§60·§61).
 * 해결: 인증 후(테넌트)는 Token Bucket — burst 허용(티켓팅), 한도는 전 테넌트 상수(§88).
 *       인증 전(signup·login·refresh)은 Fixed Window + IP — burst 불허(brute force 방지).
 *       폴링은 Token Bucket + tokenId 이고, 인증이 없어 <b>선처리</b>한다.
 * 🔴 반드시 {@link JwtAuthenticationFilter} <b>뒤에</b> 실행돼야 한다 — 인증 여부로 키를 고른다.
 *
 * @author sonix
 */
@Component
@Log4j2
public class RateLimitFilter extends OncePerRequestFilter {

    /** 폴링 버킷 용량. 재접속·화면 복귀 시의 연속 요청을 흡수할 만큼만 둔다. */
    private static final int POLL_CAPACITY = 5;

    /**
     * 이유: 폴링 버킷 키가 되는 tokenId 의 길이 상한.
     * 문제: 경로 마지막 세그먼트를 그대로 키로 써서 <b>요청 1건이 Redis 키 7,272 B</b> 였다(실측 2026-09-23, 7,000자).
     * 원인: 폴링은 인증이 없고 {@code noeviction} 이라, 종착점이 <b>같은 마스터의 다른 테넌트 enqueue 503</b> 이다.
     * 해결: 넘는 것은 {@link #OVERSIZE_POLL_TOKEN_ID} 한 키로 <b>합친다</b> — 버리지 않고 묶어 429 로 받는다.
     * 🔑 정상값은 {@code "tok_"}(4) + UUID(36) = 40 이다. 50 은 {@code CompleteRequest.admitToken} 과 같은 값.
     *
     * @author sonix
     */
    private static final int MAX_POLL_TOKEN_ID_LENGTH = 50;

    /**
     * 상한을 넘는 tokenId 가 <b>공유</b>하는 버킷 이름.
     *
     * <p>🔑 거부가 아니라 <b>합치기</b>인 이유: 여기서 400 을 내려면 필터에 새 응답 경로가 생기고,
     * 길이만 정상인 위조 tokenId 는 여전히 키를 하나씩 만든다. 합치면 키가 1개로 묶이고
     * 정상 폴링과 같은 판정기(429)가 그대로 받는다 — <b>새 장치가 0이다.</b>
     */
    private static final String OVERSIZE_POLL_TOKEN_ID = "__oversize__";

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
     * 이유: 큐 상태 제어와 API Key 관리의 한도 — 분당 60회. 실사용엔 사실상 무제한이고 남용은 막힌다.
     * 🔑 숫자보다 <b>데이터 평면과 지갑이 다르다는 사실</b>이 중요하다 — enqueue 가 몰려도 이 버킷은 줄지 않는다(§92).
     *
     * @author sonix
     */
    static final int CONTROL_CAPACITY = 60;
    static final double CONTROL_REFILL_PER_SEC = 1.0;

    /**
     * 제어 평면 경로. <b>명시적으로 열거한 것만</b> 제어로 본다.
     *
     * <p>🔴 반대로(데이터 평면을 열거하고 나머지를 제어로) 짜면 안 된다 — 빠뜨린 핫패스가
     * 60/분에 걸려 <b>플랫폼이 죽는다</b>. 이쪽으로 짜면 빠뜨려도 오늘 동작 그대로다.
     *
     * <p>🪤 {@code /api/v1/queues/{id}/tokens}(enqueue)·{@code /admit} 은 같은 접두사를 갖는다.
     * 그래서 접두사 검사가 아니라 <b>경로 전체 모양</b>으로 가른다.
     */
    private static final java.util.regex.Pattern CONTROL_PLANE = java.util.regex.Pattern.compile(
            "^/api/v1/(queues(/[^/]+(/(pause|resume))?)?|tenants/me/api-keys(/[^/]+)?)$");

    static boolean isControlPlane(String path) {
        return path != null && CONTROL_PLANE.matcher(path).matches();
    }

    /**
     * 배출 평면 경로 — {@code admit} · {@code verify} · {@code complete}.
     *
     * <p>🪤 {@code /tokens} 로 끝나면 enqueue(유입)이고, {@code /tokens/&#123;id&#125;/complete} 는
     * 배출이다. 접미사가 아니라 <b>모양 전체</b>로 갈라야 한다.
     */
    private static final java.util.regex.Pattern DRAIN_PLANE = java.util.regex.Pattern.compile(
            "^/api/v1/queues/[^/]+/(admit|admit-tokens/[^/]+/verify|tokens/[^/]+/complete)$");

    static boolean isDrainPlane(String path) {
        return path != null && DRAIN_PLANE.matcher(path).matches();
    }

    /**
     * 이유: 테넌트 한도 — <b>모든 테넌트에 동일</b>(§88 에서 등급제를 걷어냈다). 유입(enqueue) 지갑이다(§92).
     * 문제: 100,000 에서는 리미터가 한 건도 막지 않았다(3,000rps × 30초가 capacity 안, 429 0건).
     * 해결: 50,000 으로 내렸다(§89) — 같은 공격이 23.1초에 개입해 15,000건을 막는다.
     * 🪤 refill 은 <b>833.34</b> 다(833.33 이면 TTL 이 121초로 어긋나는데 테스트가 못 잡는다, §89).
     *
     * @author sonix
     */
    static final int TENANT_CAPACITY = 50_000;
    static final double TENANT_REFILL_PER_SEC = 833.34;

    private final RateLimiter tokenBucketRateLimiter;
    private final FixedWindowRateLimiter fixedWindowRateLimiter;
    private final TenantRepository tenantRepository;
    private final TenantCache tenantCache;

    /**
     * 이유: 실측용 오버라이드. 기본값(0 이하)이면 위 상수 그대로다 — <b>운영 값은 §89 의 50,000</b>.
     * 문제: 부하 실측의 목표는 <b>플랫폼 천장</b>인데 테넌트 한도가 먼저 걸리면 리미터를 재게 된다.
     * 해결: 실측 때만 올린다(2026-09-16 판의 천장이 24테넌트 × 833.34 = 19,992/s 였다).
     * 🪤 <b>둘을 같이 바꿔라</b> — {@code capacity = refill × 60} 을 깨도 고장나진 않지만 버킷 키가
     *    2분 대신 <b>1시간</b> 산다. 계약 테스트는 <b>기본값만</b> 보므로 여기서 깨도 빨개지지 않는다.
     */
   private final int tenantCapacity;
    private final double tenantRefillPerSec;

    public RateLimitFilter(
            RateLimiter tokenBucketRateLimiter,
            FixedWindowRateLimiter fixedWindowRateLimiter,
            TenantRepository tenantRepository,
            TenantCache tenantCache,
            @Value("${queue.ratelimit.tenant.capacity:0}") int tenantCapacityOverride,
            @Value("${queue.ratelimit.tenant.refill-per-sec:0}") double tenantRefillOverride
    ){
        this.tokenBucketRateLimiter = tokenBucketRateLimiter;
        this.fixedWindowRateLimiter = fixedWindowRateLimiter;
        this.tenantRepository = tenantRepository;
        this.tenantCache = tenantCache;
        this.tenantCapacity = tenantCapacityOverride > 0 ? tenantCapacityOverride : TENANT_CAPACITY;
        this.tenantRefillPerSec = tenantRefillOverride > 0 ? tenantRefillOverride : TENANT_REFILL_PER_SEC;
        if (this.tenantCapacity != TENANT_CAPACITY || this.tenantRefillPerSec != TENANT_REFILL_PER_SEC) {
            log.warn("테넌트 rate limit 오버라이드: capacity={} refill={}/s (기본 {} / {}/s)",
                    this.tenantCapacity, this.tenantRefillPerSec, TENANT_CAPACITY, TENANT_REFILL_PER_SEC);
        }
    }


    /**
     * 이유: 경로 판정에 쓰는 <b>유일한</b> 문자열원.
     * 문제: 🔴 {@code getRequestURI()} 를 쓰면 디스패처와 다른 문자열을 봐 <b>한도가 통째로 사라진다</b>.
     *       2026-08-28 에 실제로 뚫렸다 — {@code /tenants/log%69n} 15회가 한도 없이 통과했다.
     * 해결: 🔑 목표는 "완전한 디코딩"이 아니라 <b>"같은 문자열"</b>이다 — {@link UrlPathHelper} 가
     *       Spring MVC 와 같은 정규화를 한다. 인코딩을 목록으로 막는 방향은 목록이 언젠가 어긋난다.
     *
     * @author sonix
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

        // 이유: 폴링은 인증이 없어 여기서 **선처리**한다.
        // 문제: 뒤로 미루면 "늦게 걸리는" 게 아니라 **한도가 통째로 사라진다**.
        // 원인: 아래 3)에서 auth == null → resolvePublicEndpoint 가 null → **그냥 통과**한다.
        // 🔑 키를 테넌트와 따로 두는 것은 별개 결정이다 — 합치면 폴링이 enqueue·admit 예산을 먹는다.
        if (isPollPath(request.getMethod(), path)) {
            if (!checkPollRateLimit(path, response)) {
                return;   // 429로 종료
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 이유: 공개 endpoint(signup·login·refresh)는 인증 여부와 무관하게 IP Fixed Window 를 먼저 태운다.
        // 문제: 3)의 else 에 맡기면 **클라이언트가 한도를 고를 수 있다**.
        // 원인: /login 은 permitAll 이라 Authorization 헤더만 붙이면 "인증된 요청"으로 분기해
        //       brute force 가 LOGIN(10/분/IP) 대신 공격자 자신의 테넌트 버킷을 먹는다.
        // 해결: 여기서 먼저 태운다 — 로그인 시도의 신원은 **body 의 email** 이지 헤더의 토큰이 아니다.
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
            if (!checkAuthenticatedRateLimit(tenantAuth, path, response)) {
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
     * 이유: tokenId 기준 Token Bucket. 폴링은 인증이 없어 이 키가 유일한 구분자다.
     * 문제: 없는 tokenId 도 버킷을 만든다 — 무작위로 쏘면 요청 1건 = 새 키 1개다(실측 200건 → +200, TTL 65초).
     * 원인: 종착점이 {@code noeviction} 이라 <b>같은 마스터의 다른 테넌트가 503</b> 을 받는다.
     * 해결: 키 하나의 크기에 상한을 둔다({@link #MAX_POLL_TOKEN_ID_LENGTH}). tokenId 는 <b>정규화된 경로</b>에서 뽑는다. §96-5
     *
     * @author sonix
     * @return true=통과, false=거부(429 완료).
     */
    private boolean checkPollRateLimit(String path, HttpServletResponse res)
            throws IOException {
        String tokenId = path.substring(path.lastIndexOf('/') + 1);   // 마지막 세그먼트
        if (tokenId.length() > MAX_POLL_TOKEN_ID_LENGTH) {
            tokenId = OVERSIZE_POLL_TOKEN_ID;   // 정상값은 40자다. 넘으면 한 버킷으로 합친다
        }
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
            TenantAuth tenantAuth, String path, HttpServletResponse response) throws IOException {

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

        // 🔑 제어 평면은 **지갑을 따로 쓴다.** 같은 키를 쓰면 enqueue 가 한도를 다 쓴 순간
        //    pause 가 429 가 되는데, 멈춰야 하는 순간이 곧 부하가 몰린 순간이라
        //    비상 스위치가 정확히 필요할 때 안 눌린다(2026-09-16 AWS 실측).
        String key;
        int capacity;
        double refill;
        if (isControlPlane(path)) {
            key = RateLimitKeys.tenantControl(tenant.getTenantId());
            capacity = CONTROL_CAPACITY;
            refill = CONTROL_REFILL_PER_SEC;
        } else if (isDrainPlane(path)) {
            // 🔑 배출(admit·verify·complete)은 유입과 지갑을 나눈다. 같이 쓰면 유입이 한도를
            //    비운 순간 줄이 안 빠지고, 안 빠지면 더 쌓이는 악순환이 된다.
            key = RateLimitKeys.tenantDrain(tenant.getTenantId());
            capacity = tenantCapacity;
            refill = tenantRefillPerSec;
        } else {
            key = RateLimitKeys.tenant(tenant.getTenantId());
            capacity = tenantCapacity;
            refill = tenantRefillPerSec;
        }

        boolean allowed = tokenBucketRateLimiter.tryAcquire(key, capacity, refill);

        if (!allowed) {
            log.debug("Token Bucket rate limit exceeded: key={}", key);
            // Retry-After: refill 기반 (1 토큰 회복 시간)
            long retryAfter = Math.max(1, (long) Math.ceil(1.0 / refill));
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
