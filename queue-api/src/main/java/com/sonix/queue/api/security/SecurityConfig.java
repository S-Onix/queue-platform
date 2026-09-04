package com.sonix.queue.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            RateLimitFilter rateLimitFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 공개 폴링 GET 둘만 연다 — PublicPollingCorsConfig
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // === ERROR 디스패치 ===
                        // 🔴 없으면 검증 실패가 401로 위장된다(실측). Spring Boot는 400/404/405를
                        //    /error로 forward하는데, 시큐리티 필터가 ERROR 디스패치에도 걸려
                        //    anyRequest().authenticated()에 잡힌다. 그러면 signup에 빈 body를 보내도
                        //    "인증이 필요합니다" 401이 나가고, 호출자는 자격 증명을 의심하며 헤맨다.
                        //    permitAll이어도 /error가 내는 본문은 Boot의 표준 에러 응답이라 정보 노출이 아니다.
                        .requestMatchers("/error").permitAll()

                        // === 공개 엔드포인트 (HTTP Method 명시) ===
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/refresh").permitAll()

                        // === Actuator 제한 ===
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").denyAll()    // 그 외 actuator 차단

                        // === token의 polling은 api-key인증을 거치지 않는다 ===
                        .requestMatchers(HttpMethod.GET, "/api/v1/queues/*/tokens/*").permitAll()
                        // === 큐 전광판도 인증 없음 (§79) ===
                        // queueId는 대기 페이지 JS에 박히는 공개값이라 인증으로 막을 게 없고,
                        // 노출되는 것은 admit 진행률과 pacing 표뿐이다. Rate Limit도 일부러 안 건다
                        // (RateLimitFilter가 미등록 public 경로를 통과시킨다 — QueueEngineController.status 참조).
                        .requestMatchers(HttpMethod.GET, "/api/v1/queues/*/status").permitAll()
                        // === 그 외 모두 인증 필요 ===
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write(
                                    "{\"error\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다\"}"
                            );
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write(
                                    "{\"error\":\"FORBIDDEN\",\"message\":\"권한이 없습니다\"}"
                            );
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
