package com.sonix.queue.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 만료 시간 설정 (application.yml: jwt.*)
 *
 * 표기:
 *   jwt:
 *     access-token-expiry: 15m
 *     refresh-token-expiry: 7d
 *
 * Note:
 *   같은 prefix("jwt")를 사용하는 {@link JwtKeyStore}와 공존한다.
 *   각자 자기 필드(activeKid/keys vs accessTokenExpiry/refreshTokenExpiry)만 바인딩되므로 충돌 없음.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        Duration accessTokenExpiry,
        Duration refreshTokenExpiry
) {
}
