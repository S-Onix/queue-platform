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

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // === 공개 엔드포인트 (HTTP Method 명시) ===
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/refresh").permitAll()

                        // === Actuator 제한 ===
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()    // 그 외 actuator 차단

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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
