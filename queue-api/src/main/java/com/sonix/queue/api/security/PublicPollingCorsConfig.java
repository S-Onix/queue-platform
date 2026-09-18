package com.sonix.queue.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 이유: 대기 페이지가 쓰는 <b>공개 폴링 GET 둘</b>에만 CORS 를 연다.
 * 원인: 원칙 2 가 "브라우저가 Platform 을 직접 폴링한다"라 대기 페이지 오리진과 Platform 이 다르다 —
 *       헤더가 없으면 브라우저가 응답 본문을 JS 에 주지 않는다.
 * 해결: {@code *} 다 — Tenant 오리진을 알 수 없고, 비인증 경로라 <b>누구나 볼 수 있는 값</b>뿐이다.
 * 🔴 <b>다른 경로를 추가하지 마라</b> — 나머지는 키를 싣는다. {@code Retry-After} 는 명시 노출이 필요하다.
 *
 * @author sonix
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
