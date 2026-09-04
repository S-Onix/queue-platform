package com.sonix.queue.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS를 여는 경로가 <b>공개 폴링 GET 둘뿐</b>인지 고정한다.
 *
 * <p>넓어지면 브라우저에 {@code X-API-Key}를 두는 통합이 가능해지고, 좁아지면(=빠지면)
 * 대기 페이지가 다른 오리진에서 응답을 못 읽는다. 둘 다 조용히 일어나 회귀를 눈치채기 어렵다.
 */
class PublicPollingCorsConfigTest {

    private final CorsConfigurationSource source = new PublicPollingCorsConfig().corsConfigurationSource();

    private CorsConfiguration configFor(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.addHeader("Origin", "https://tenant.example.com");
        return source.getCorsConfiguration(req);
    }

    @Test
    @DisplayName("전광판·개인 폴링은 어떤 오리진에서도 GET으로 읽을 수 있다")
    void publicPollingIsOpen() {
        for (String uri : new String[]{"/api/v1/queues/q1/status", "/api/v1/queues/q1/tokens/tk1"}) {
            CorsConfiguration config = configFor(uri);
            assertThat(config).as(uri).isNotNull();
            assertThat(config.checkOrigin("https://tenant.example.com")).isEqualTo("https://tenant.example.com");
            assertThat(config.checkHttpMethod(org.springframework.http.HttpMethod.GET)).isNotNull();
        }
    }

    @Test
    @DisplayName("🪤 Retry-After를 노출한다 — 안전 목록이 아니라 안 열면 교차 오리진에서 null이다")
    void exposesRetryAfter() {
        assertThat(configFor("/api/v1/queues/q1/tokens/tk1").getExposedHeaders()).containsExactly("Retry-After");
    }

    @Test
    @DisplayName("🔴 그 밖의 경로는 열지 않는다 — 키를 싣는 경로다")
    void everythingElseStaysClosed() {
        assertThat(configFor("/api/v1/queues/q1/tokens")).isNull();          // enqueue (POST, X-API-Key)
        assertThat(configFor("/api/v1/queues/q1/admit")).isNull();
        assertThat(configFor("/api/v1/queues")).isNull();
        assertThat(configFor("/api/v1/tenants/login")).isNull();
        assertThat(configFor("/actuator/prometheus")).isNull();
    }

    @Test
    @DisplayName("자격 증명은 싣지 않는다 — 쿠키가 없어야 오리진 * 가 안전하다")
    void credentialsStayOff() {
        assertThat(configFor("/api/v1/queues/q1/status").getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }
}
