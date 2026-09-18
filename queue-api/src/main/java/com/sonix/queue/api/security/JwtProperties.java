package com.sonix.queue.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 이유: JWT 만료 시간 설정({@code jwt.access-token-expiry} · {@code refresh-token-expiry}).
 * 🪤 같은 prefix {@code jwt} 를 {@link JwtKeyStore} 와 공유한다 — 각자 자기 필드만 바인딩한다.
 *
 * @author sonix
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Duration accessTokenExpiry,
        Duration refreshTokenExpiry
) {
}
