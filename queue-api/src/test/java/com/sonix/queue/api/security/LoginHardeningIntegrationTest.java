package com.sonix.queue.api.security;

import com.sonix.queue.api.queue.EnqueueE2ETestConfig;
import com.sonix.queue.domain.tenant.PasswordHasher;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.ratelimit.RateLimitKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 방어 3종의 <b>통합</b> 회귀 테스트 — 실제 필터 체인 · 실제 Redis · 실제 MySQL.
 *
 * <p><b>왜 목이 아니라 통합인가.</b> 같은 명제를 목으로 검증하는 테스트가 이미 있다
 * ({@link RateLimitFilterIpSourceTest}, {@code TenantServiceTest}). 그런데 그것들이
 * 못 잡는 구간이 셋 남는다.
 * <ul>
 *   <li><b>필터가 체인에 실제로 꽂혀 있는지</b> — 기존 컨트롤러 테스트는 전부
 *       {@code @MockBean RateLimitFilter} + {@code addFilters = false}라, {@code SecurityConfig}의
 *       {@code addFilterAfter(rateLimitFilter, ...)} 한 줄을 지워도 전체가 초록이다.
 *       그래서 여기서는 {@code addFilters = false}를 <b>쓰지 않는다.</b></li>
 *   <li><b>한도 숫자가 실제로 강제되는지</b> — 목 테스트는 {@code tryAcquire}가 어떤 키·인자로
 *       불리는지만 본다. 반환값을 늘 {@code true}로 스텁하므로 "11번째가 정말 429가 되는가"는
 *       한 번도 확인되지 않는다. Redis Cluster가 키를 거부하거나(과거 CROSSSLOT 전례),
 *       Lua가 카운트를 안 세도 목 테스트는 초록이다.</li>
 *   <li><b>JwtAuthenticationFilter가 먼저 돌아 컨텍스트를 채운 상태</b> — 목 테스트는
 *       SecurityContext를 손으로 채워 그 상황을 <i>흉내</i>낸다. {@code /login}이
 *       {@code permitAll}인데도 Bearer 토큰이 실제로 파싱되어 컨텍스트가 차는지는 체인을 태워야 안다.
 *       <br>⚠️ 정확히 말하면 <b>이 테스트가 그 전제를 단언하지는 않는다.</b> 확인은 결함 주입으로 했다 —
 *       공개 endpoint 선처리 블록을 지우자 11번째가 429가 아니라 401로 통과했고(2026-08-26 실측),
 *       그건 인증 분기로 샜다는 뜻이므로 컨텍스트가 실제로 차 있었다는 증거다.</li>
 * </ul>
 *
 * <p><b>데이터 소유.</b> 이 머신의 MySQL·Redis는 다른 에이전트와 공유한다. 그래서
 * <ul>
 *   <li>테넌트 이메일은 전부 {@code it_auth_} 접두 → {@code @AfterAll}에서 그 접두만 지운다</li>
 *   <li>Rate Limit 키는 <b>IP에서 파생</b>되므로 접두를 붙일 수 없다. 대신 테스트마다
 *       {@code 198.51.100.x}(RFC 5737 TEST-NET-2, 실존하지 않는 문서용 대역)를 하나씩 준다.
 *       실제 요청은 전부 {@code 127.0.0.1}에서 오므로 남의 버킷과 절대 겹치지 않고,
 *       테스트끼리도 예산을 나눠 쓰지 않는다.</li>
 *   <li>{@code rl:*} Fixed Window 키는 TTL 61초로 자멸하므로 따로 지우지 않는다
 *       ({@code fixed-window.lua}). 토큰 버킷 키만 명시 삭제한다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc   // ⚠️ addFilters = false 금지 — 필터 체인이 검증 대상이다
@TypeExcludeFilters(LoginHardeningIntegrationTest.ExcludeStandaloneTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("mysql")
@Tag("redis")
class LoginHardeningIntegrationTest {

    /**
     * queue-api 테스트 소스의 {@code @Configuration} 하나를 스캔에서 제외한다.
     *
     * <p>{@code QueueApiApplication}은 {@code scanBasePackages = "com.sonix.queue"}이고, 스캔은
     * 테스트 클래스패스도 훑는다. {@code EnqueueE2ETestConfig}는 {@code TenantRepository}·
     * {@code QueueRepository}·{@code EnqueueEventPublisher}를 인메모리/no-op으로 <b>다시</b>
     * 정의하는 전용 부트스트랩({@code @SpringBootTest(classes = ...)}로 명시 지정해 쓴다)이라,
     * 전체 컨텍스트를 띄우면 진짜 빈과 충돌한다({@code NoUniqueBeanDefinitionException}).
     * 그대로 두면 인메모리 스텁이 이겨서 <b>MySQL을 안 쓰는 "통합" 테스트</b>가 될 수도 있다.
     *
     * <p>Boot가 자동으로 빼주는 것은 {@code @TestConfiguration}이 붙은 것뿐이다
     * ({@code TestTypeExcludeFilter}). 저 클래스는 평범한 {@code @Configuration}이라 여기서 뺀다.
     */
    static class ExcludeStandaloneTestConfig extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return EnqueueE2ETestConfig.class.getName().equals(reader.getClassMetadata().getClassName());
        }

        // TypeExcludeFilter는 컨텍스트 캐시 키의 일부라 equals/hashCode를 강제한다(구현 안 하면 기동 실패).
        @Override public boolean equals(Object obj) { return obj != null && getClass() == obj.getClass(); }
        @Override public int hashCode() { return getClass().hashCode(); }
    }

    private static final String EMAIL_PREFIX = "it_auth_";

    /**
     * 실행마다 IP 대역을 새로 뽑는다.
     *
     * <p>Fixed Window 카운터는 TTL 61초로 <b>테스트가 끝난 뒤에도 살아 있다.</b> IP를 상수로 박으면
     * 1분 안에 재실행할 때 첫 요청부터 429가 나온다(실제로 그렇게 깨졌다). 지울 수도 없다 —
     * 지우는 순간 "한도가 실제로 강제되는가"라는 검증 자체가 뒤 실행에서 무의미해진다.
     */
    private static final int IP_BASE = 10 + new java.util.Random().nextInt(230);

    /** 계정 준비(가입·로그인) 전용 IP. 검증용 IP의 한도 예산을 건드리지 않게 분리한다. */
    private static final String IP_SETUP = testNetIp(IP_BASE);
    private static final String IP_LOGIN_FLOOD = testNetIp(IP_BASE + 1);
    private static final String IP_SIGNUP_POLICY = testNetIp(IP_BASE + 2);
    private static final String IP_LEGACY_LOGIN = testNetIp(IP_BASE + 3);
    private static final String IP_ENUMERATION = testNetIp(IP_BASE + 4);

    /** RFC 5737 TEST-NET-2. 실존하지 않는 문서용 대역이라 누구의 실제 트래픽과도 겹치지 않는다. */
    private static String testNetIp(int lastOctet) {
        return "198.51.100." + lastOctet;
    }

    private static final String GOOD_PASSWORD = "Str0ngPassw0rd!2026";   // 19자 (>= 12)

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private DataSource dataSource;
    /** Rate Limit 키는 전부 cluster1(@Primary)에 있다 — {@code RedisConfig} 참조. */
    @Autowired @Qualifier("stringRedisTemplate") private StringRedisTemplate redis;

    private String mainEmail;
    private String mainTenantId;
    private String mainAccessToken;
    /** 계정 열거 테스트 전용 계정. main과 나누는 이유는 아래 @BeforeAll 주석 참조. */
    private String probeEmail;
    private final List<String> createdTenantIds = new ArrayList<>();

    /**
     * 계정 둘을 REST 전 경로로 만들고, 로그인은 <b>계정당 한 번씩만</b> 한다.
     *
     * <p>SQL 직접 시드 대신 REST를 쓰는 이유는 전례다 — 시드가 필터 결함(D-1)을 숨긴 적이 있다.
     *
     * <p>🔴 <b>계정을 나누고 로그인 횟수를 최소로 두는 이유:</b> 같은 테넌트가 <b>같은 초</b>에 두 번
     * 로그인하면 Refresh JWT가 바이트 단위로 동일해진다({@code JwtProvider}가 {@code jti}·nonce 없이
     * sub·tenantId·type·iat(초)·exp만 담는다). 그러면 {@code refresh_tokens.token_hash} UNIQUE에
     * 걸려 500이 난다. 실제로 이 테스트를 쓰다가 재현했다(보고 대상 — 운영 코드는 고치지 않았다).
     * 테스트가 그 결함을 밟고 깨지면 정작 검증하려던 명제가 가려지므로 경로를 피해 간다.
     */
    @BeforeAll
    void createAccountsViaRest() throws Exception {
        mainEmail = EMAIL_PREFIX + System.nanoTime() + "@example.com";
        probeEmail = EMAIL_PREFIX + System.nanoTime() + "@example.com";
        signup(mainEmail);
        signup(probeEmail);

        mainTenantId = tenantRepository.findByEmail(mainEmail).orElseThrow().getTenantId();
        createdTenantIds.add(mainTenantId);
        createdTenantIds.add(tenantRepository.findByEmail(probeEmail).orElseThrow().getTenantId());

        mainAccessToken = loginAndExtractAccessToken(mainEmail, GOOD_PASSWORD, IP_SETUP);
    }

    private void signup(String email) throws Exception {
        mockMvc.perform(post("/api/v1/tenants/signup", IP_SETUP).content(signupBody(email, GOOD_PASSWORD)))
                .andExpect(status().isOk());
    }

    @AfterAll
    void cleanUpOwnDataOnly() throws Exception {
        // ⚠️ 접두 한정 삭제. 전체 DELETE·TRUNCATE는 다른 에이전트의 검증을 통째로 깬다.
        //    queue-api에는 spring-jdbc가 없어 JDBC를 그대로 쓴다(의존성을 늘릴 이유가 없다).
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM refresh_tokens WHERE tenant_id IN "
                    + "(SELECT id FROM tenants WHERE email LIKE 'it\\_auth\\_%')");
            st.executeUpdate("DELETE FROM tenants WHERE email LIKE 'it\\_auth\\_%'");
        }

        createdTenantIds.forEach(id -> redis.delete(RateLimitKeys.tenant(id)));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1) 공개 endpoint Rate Limit 우회 차단
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>유효한 Access 토큰을 붙여도 /login은 LOGIN(10/분/IP)을 탄다.</b>
     *
     * <p>{@code /login}은 {@code permitAll}이고 {@code JwtAuthenticationFilter}는 경로를 안 가린다.
     * 그래서 Bearer를 붙이면 SecurityContext가 채워진 채로 RateLimitFilter에 도달하고,
     * 예전엔 그 순간 "인증된 요청" 분기로 빠져 <b>자기 테넌트 버킷(FREE capacity 100)</b>을 썼다.
     * 10배이고, 계정을 K개 만들면 버킷도 K개라 IP 기준 한도가 사실상 사라진다.
     *
     * <p>이 테스트가 없으면 {@code RateLimitFilter}의 공개 endpoint 선처리 블록을 지워도
     * 전체가 초록이다. 목 테스트는 스텁이 늘 통과를 반환해 <b>11번째가 실제로 막히는지</b>를
     * 못 본다.
     */
    @Test
    @DisplayName("인증된 상태로 /login을 두들겨도 11번째는 429 — 테넌트 버킷으로 새지 않는다")
    void authenticatedLoginStillBurnsIpWindow() throws Exception {
        // Fixed Window는 (now / 60_000) 경계에서 카운터가 리셋된다. 경계에 걸치면 11번째가
        // 새 윈도우의 1번째가 되어 통과한다 — 테스트가 아니라 시계 때문에 깨지는 경우다.
        awaitFreshWindowIfNearBoundary();

        // 존재하지 않는 이메일로 두들긴다: bcrypt를 안 타서 빠르고(윈도우 안에 확실히 들어간다),
        // refresh_tokens 행도 남기지 않는다.
        String body = loginBody(EMAIL_PREFIX + "nobody@example.com", "whatever-password");

        for (int attempt = 1; attempt <= 10; attempt++) {
            int statusCode = mockMvc.perform(post("/api/v1/tenants/login", IP_LOGIN_FLOOD)
                            .header("Authorization", "Bearer " + mainAccessToken)
                            .content(body))
                    .andReturn().getResponse().getStatus();
            // 여기서 보는 것은 "막히지 않았다" 하나뿐이다. 정확한 실패 코드(T003)는 계정 열거
            // 테스트의 몫이라, 그걸 여기서도 단언하면 무관한 결함 하나에 두 테스트가 같이 깨진다.
            assertThat(statusCode)
                    .as("LOGIN 한도 10 안쪽(%d번째)은 통과해야 한다", attempt)
                    .isNotEqualTo(429);
        }

        MvcResult blocked = mockMvc.perform(post("/api/v1/tenants/login", IP_LOGIN_FLOOD)
                        .header("Authorization", "Bearer " + mainAccessToken)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RL001"))
                .andReturn();
        assertThat(blocked.getResponse().getHeader("Retry-After")).isEqualTo("60");

        // 어느 버킷을 썼는지까지 못박는다. 429만 보면 "테넌트 버킷(100)이 우연히 찼다"와
        // 구분되지 않는다 — 아래 두 단언이 그 구분을 만든다.
        String windowKey = RateLimitKeys.fixedWindow(
                RateLimitKeys.publicEndPoint("login", IP_LOGIN_FLOOD),
                System.currentTimeMillis(), 60_000L);
        assertThat(redis.opsForValue().get(windowKey))
                .as("LOGIN IP 윈도우 카운터가 실제로 올라가야 한다")
                .isEqualTo("11");
        assertThat(redis.hasKey(RateLimitKeys.tenant(mainTenantId)))
                .as("공개 endpoint가 테넌트 토큰 버킷을 건드리면 안 된다")
                .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2) 비밀번호 최소 12자 (signup에만)
    // ────────────────────────────────────────────────────────────────────────

    /** 이 테스트가 없으면 {@code SignupRequest}의 {@code @Size(min = 12)}를 지워도 아무도 안 막는다. */
    @Test
    @DisplayName("가입은 11자를 400으로 거부하고 12자를 받는다")
    void signupEnforcesMinimumPasswordLength() throws Exception {
        // 길이는 세서 쓰지 말고 만들어 쓴다. 손으로 센 "twelvechars"가 실제로는 11자라
        // 처음에 이 테스트가 엉뚱하게 깨졌다 — 경계 테스트에서 가장 흔한 자책골이다.
        mockMvc.perform(post("/api/v1/tenants/signup", IP_SIGNUP_POLICY)
                        .content(signupBody(EMAIL_PREFIX + "short" + System.nanoTime() + "@example.com",
                                "a".repeat(11))))     // 경계 바로 아래
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/tenants/signup", IP_SIGNUP_POLICY)
                        .content(signupBody(EMAIL_PREFIX + "twelve" + System.nanoTime() + "@example.com",
                                "a".repeat(12))))     // 경계
                .andExpect(status().isOk());
    }

    /**
     * 🔴 <b>{@code LoginRequest}에는 길이 제약이 없어야 한다.</b>
     *
     * <p>걸면 정책 변경 전에 만들어진 짧은 비밀번호 계정이 로그인 자체를 못 하고,
     * 400 응답이 정책을 그대로 노출한다. 이 계정은 지금의 signup 경로로는 만들 수 없어서
     * (400에 막힌다) <b>어쩔 수 없이</b> 도메인 포트로 직접 시드한다 — REST로 재현 불가능한
     * 유일한 케이스다.
     */
    @Test
    @DisplayName("정책 이전에 만들어진 6자 비밀번호 계정도 로그인은 된다")
    void loginHasNoLengthConstraint() throws Exception {
        String legacyEmail = EMAIL_PREFIX + "legacy" + System.nanoTime() + "@example.com";
        Tenant legacy = tenantRepository.save(
                Tenant.create(legacyEmail, passwordHasher.hash("old123"), "레거시 테넌트"));
        createdTenantIds.add(legacy.getTenantId());

        mockMvc.perform(post("/api/v1/tenants/login", IP_LEGACY_LOGIN)
                        .content(loginBody(legacyEmail, "old123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3) 계정 열거 차단
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 없는 이메일(예전 404 T002)과 틀린 비밀번호(401 T003)가 <b>구분되지 않아야</b> 한다.
     * 구분되면 응답 한 번으로 실존 계정 목록을 만든 뒤 10/분 예산을 그쪽에만 쓸 수 있다.
     *
     * <p>맞는 비밀번호로 200을 먼저 확인하는 이유: 그게 없으면 이메일 오타 하나로 두 케이스가
     * 모두 "없는 이메일"이 되어 <b>테스트가 공허하게 통과</b>한다.
     */
    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호가 똑같은 401 T003으로 답한다 (계정 열거 차단)")
    void unknownEmailAndWrongPasswordAreIndistinguishable() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/login", IP_ENUMERATION)
                        .content(loginBody(probeEmail, GOOD_PASSWORD)))
                .andExpect(status().isOk());   // 이 계정이 실존함을 먼저 확정

        MvcResult unknownEmail = mockMvc.perform(post("/api/v1/tenants/login", IP_ENUMERATION)
                        .content(loginBody(EMAIL_PREFIX + "never_registered@example.com", GOOD_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorResponse.code").value("T003"))
                .andReturn();

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/tenants/login", IP_ENUMERATION)
                        .content(loginBody(probeEmail, "Wr0ngPassw0rd!2026")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorResponse.code").value("T003"))
                .andReturn();

        // 메시지까지 같아야 한다. 코드만 맞추고 문구가 "비밀번호가 틀렸습니다"로 남으면
        // 누출은 그대로다.
        assertThat(messageOf(unknownEmail)).isEqualTo(messageOf(wrongPassword));
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    /** MockMvc 요청의 TCP peer를 바꾼다. Rate Limit 키가 remoteAddr에서 나오기 때문이다. */
    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder post(String path, String ip) {
        return MockMvcRequestBuilders.post(path).with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON);
    }

    private String loginAndExtractAccessToken(String email, String password, String ip) throws Exception {
        String json = mockMvc.perform(post("/api/v1/tenants/login", ip).content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll("(?s).*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private static String messageOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("(?s).*\"message\"\\s*:\\s*\"([^\"]*)\".*", "$1");
    }

    private static String signupBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"name\":\"IT 테넌트\"}";
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    /** 윈도우 잔여가 10초 미만이면 다음 윈도우로 넘긴다 (11회 호출은 1초도 안 걸린다). */
    private static void awaitFreshWindowIfNearBoundary() throws InterruptedException {
        long remaining = 60_000L - (System.currentTimeMillis() % 60_000L);
        if (remaining < 10_000L) {
            Thread.sleep(remaining + 100L);
        }
    }
}
