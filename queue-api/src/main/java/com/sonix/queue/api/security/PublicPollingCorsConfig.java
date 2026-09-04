package com.sonix.queue.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 대기 페이지가 쓰는 <b>공개 폴링 GET 둘</b>에만 CORS를 연다.
 *
 * <p><b>왜 필요한가.</b> 설계 원칙 2가 "유저 브라우저가 Platform을 직접 폴링한다"인데, 대기 페이지는
 * Tenant 오리진이고 Platform은 다른 오리진이다. 두 GET은 단순 요청이라 서버까지 도달은 하지만
 * {@code Access-Control-Allow-Origin}이 없으면 브라우저가 JS에게 본문을 주지 않는다.
 *
 * <p><b>왜 {@code *} 인가.</b> Tenant의 대기 페이지 오리진은 Platform이 알 수 없고, 새 Tenant마다
 * 서버 설정을 고치는 구조가 되면 온보딩이 막힌다. 자격 증명을 안 싣는(비인증) 공개 엔드포인트라
 * {@code *}가 여는 것은 "이미 누구나 볼 수 있는 값"뿐이다 — admit 진행률과 pacing 표, 그리고
 * tokenId를 아는 사람만 얻는 자기 순번.
 *
 * <p>🔴 <b>여기에 다른 경로를 추가하지 마라.</b> 나머지는 전부 {@code X-API-Key}나 JWT를 싣는
 * 경로다. {@code allowCredentials}가 꺼져 있어도, 열어 두면 브라우저에 키를 두는 통합을 부추긴다.
 *
 * <p>🪤 {@code Retry-After}는 <b>안전 목록이 아니다.</b> 노출하지 않으면 교차 오리진에서
 * {@code headers.get("Retry-After")}가 {@code null}이라, SDK가 429 뒤 얼마나 쉬어야 하는지 모른 채
 * 기본 간격으로 다시 두드린다.
 */
@Configuration
public class PublicPollingCorsConfig {

    static final List<String> PUBLIC_POLLING_PATHS = List.of(
            "/api/v1/queues/*/status",
            "/api/v1/queues/*/tokens/*"
    );

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        PUBLIC_POLLING_PATHS.forEach(path -> source.registerCorsConfiguration(path, config));
        return source;
    }
}
