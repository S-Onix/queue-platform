package com.sonix.queue.api.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Log4j2
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null) {
                try {
                    Claims claims = jwtProvider.parseAndValidateAccess(token);
                    Long id = Long.parseLong(claims.getSubject());
                    String tenantId = claims.get("tenantId", String.class);

                    TenantAuth tenantAuth = new TenantAuth(id, tenantId);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(tenantAuth, null, List.of());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (Exception e) {
                    // 인증 실패 — 로그만 남기고 다음 필터로 진행
                    // → SecurityConfig에서 최종 401 처리
                    log.debug("JWT authentication failed: {}", e.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        filterChain.doFilter(request, response);
    }


    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
