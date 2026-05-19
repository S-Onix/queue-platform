package com.sonix.queue.api.security;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

@Component
@Log4j2
public class JwtProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiry}")
    private Long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private Long refreshTokenExpiry;

    private SecretKey key;

    private static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "ACCESS";
    public static final String TYPE_REFRESH = "REFRESH";

    @PostConstruct
    public void init(){
        this.key  = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long id, String tenantId) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(id.toString())
                .claim("tenantId", tenantId)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiry)))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long id, String tenantId){
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(id.toString())
                . claim("tenantId", tenantId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshTokenExpiry)))
                .signWith(key)
                .compact();
    }

    /**
     * Access Token 전용 검증
     * 서명 + 만료 + type=ACCESS 확인
     */
    public Claims parseAndValidateAccess(String token) {
        Claims claims = getClaims(token);
        String type = claims.get(CLAIM_TYPE, String.class);
        if(!TYPE_ACCESS.equals(type)){
            log.warn("Token type mismatch. expected=ACCESS, actual={}", type);
            throw new BusinessException(ErrorCode.AK_001_UNAUTHORIZED);
        }
        return claims;
    }

    /**
     * Refresh Token 전용 검증
     * 서명 + 만료 + type=REFRESH 확인
     */
    public Claims parseAndValidateRefresh(String token) {
        Claims claims = getClaims(token);
        String type = claims.get(CLAIM_TYPE, String.class);
        if(!TYPE_REFRESH.equals(type)) {
            log.warn("Token type mismatch. expected=REFRESH, actual={}", type);
            throw new BusinessException(ErrorCode.AK_001_UNAUTHORIZED);
        }
        return claims;
    }

    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
