package com.sonix.queue.api.security;

import com.sonix.queue.domain.apikey.ApiKeyCache;
import com.sonix.queue.domain.apikey.ApiKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code ApiKeyAuthenticationFilter}의 경로 판정이 <b>정규화된 문자열</b>을 쓰는지에 대한 회귀 테스트.
 *
 * <p><b>왜 여기여야 하나.</b> 이 필터를 참조하는 테스트 7개가 전부 {@code @MockBean}이고, 실 빈이
 * 도는 유일한 곳({@code QueueLifecycleContractTest})은 평문 경로만 쓰는 데다
 * {@code @Tag("mysql")+@Tag("redis")}라 <b>단위 CI 레인에서 빠진다</b>. 즉 이 케이스가 없으면
 * {@code getRequestURI()} 한 줄로 되돌려도 단위 레인이 초록이다 — {@code RateLimitFilterIpSourceTest}가
 * {@code RateLimitFilter}에 대해 막고 있는 것과 정확히 같은 구멍이다.
 *
 * <p><b>무엇이 깨졌었나</b>(2026-08-28 실측). 원문으로 판정하면 디스패처가 보는 문자열과 갈린다:
 * {@code POST /api/v1/queues/q_x/tokens} → 404 Q001(정상 도달)인데
 * {@code POST /api/v1/queues/q_x/token%73} → <b>401</b>. 경로를 인코딩하는 Tenant 클라이언트는
 * enqueue·admit·verify·complete가 통째로 401이 된다.
 *
 * <p>🔑 지금은 fail-closed(401)라 우회가 아니라 오작동이지만, <b>위 4경로 중 하나라도
 * permitAll이 되는 순간 fail-open으로 뒤집힌다.</b> 그때는 {@code RateLimitFilter}가 뚫렸던 것과
 * 같은 결함이 된다.
 *
 * <p>⚠️ 목의 한계: {@code MockHttpServletRequest}는 준 문자열을 그대로 돌려주므로 {@code %73}
 * 같은 단순 인코딩만 실제 Tomcat과 결과가 같다. {@code ;x=1}·{@code //}·{@code %2F}는 라이브에서
 * <b>필터에 도달조차 못 한다</b>(Tomcat·StrictHttpFirewall이 400). 그런 입력을 여기 넣으면
 * "필터가 막았다"를 단정하게 되는데 실제 방어자는 firewall이다.
 */
class ApiKeyFilterPathNormalizationTest {

    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter(mock(ApiKeyRepository.class), mock(ApiKeyCache.class));

    /** shouldNotFilter가 protected라 같은 패키지에 둔다. false = 이 필터를 탄다(= API Key 경로). */
    private boolean filtered(String method, String uri) {
        return !filter.shouldNotFilter(new MockHttpServletRequest(method, uri));
    }

    @Test
    @DisplayName("인코딩한 Tenant 경로도 평문과 똑같이 API Key 인증을 탄다")
    void encodedTenantPathsAreStillFiltered() {
        // 't' → %74, 's' → %73. 같은 경로를 가리키지만 원문 문자열은 다르다.
        assertThat(filtered("POST", "/api/v1/queues/q_1/tokens")).isTrue();
        assertThat(filtered("POST", "/api/v1/queues/q_1/token%73")).isTrue();
        assertThat(filtered("POST", "/api/v1/queues/q_1/%74okens")).isTrue();

        assertThat(filtered("POST", "/api/v1/queues/q_1/admit")).isTrue();
        assertThat(filtered("POST", "/api/v1/queues/q_1/%61dmit")).isTrue();

        assertThat(filtered("POST", "/api/v1/queues/q_1/tokens/tok_1/complete")).isTrue();
        assertThat(filtered("POST", "/api/v1/queues/q_1/tokens/tok_1/complet%65")).isTrue();
    }

    @Test
    @DisplayName("폴링과 전광판은 인코딩해도 이 필터를 타지 않는다 (401 뭉갬 방지)")
    void pollingAndStatusStayUnfiltered() {
        // 🔴 여기가 true가 되면 유저 폴링이 401이 된다 — §80에서 실제로 났던 사고의 반대 방향이다.
        assertThat(filtered("GET", "/api/v1/queues/q_1/tokens/tok_1")).isFalse();
        assertThat(filtered("GET", "/api/v1/queues/q_1/tokens/%74ok_1")).isFalse();
        assertThat(filtered("GET", "/api/v1/queues/q_1/status")).isFalse();
        assertThat(filtered("GET", "/api/v1/queues/q_1/statu%73")).isFalse();
    }
}
