package com.sonix.queue.api.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.Date;


/**
 * JWT 발급/검증 + Key Rotation 지원
 *
 * Phase A 보안 강화:
 *   - type 클레임으로 ACCESS/REFRESH 구분 강제
 *   - parseAndValidateAccess / parseAndValidateRefresh 분리
 *
 * Key Rotation:
 *   - 발급: JwtKeyStore의 active key 사용 + 헤더에 kid 명시
 *   - 검증: 토큰 헤더의 kid로 JwtKeyStore에서 키 조회 → 검증
 *   - 옛 토큰도 검증 가능 (사용자 영향 최소)
 */
@Component
@Log4j2
public class JwtProvider {

    @Value("${jwt.access-token-expiry}")
    private Long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private Long refreshTokenExpiry;

    private final JwtKeyStore keyStore;

    private static final String CLAIM_TYPE = "type";
    public static final String CLAIM_TENANT_ID = "tenantId";
    public static final String TYPE_ACCESS = "ACCESS";
    public static final String TYPE_REFRESH = "REFRESH";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public JwtProvider(JwtKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public String generateAccessToken(Long id, String tenantId) {
        Instant now = Instant.now();

        return Jwts.builder()
                .header()
                    .keyId(keyStore.getActiveKid())
                    .and()
                .subject(id.toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiry)))
                .signWith(keyStore.getActiveKey())
                .compact();
    }

    public String generateRefreshToken(Long id, String tenantId){
        Instant now = Instant.now();

        return Jwts.builder()
                .header()
                    .keyId(keyStore.getActiveKid())
                    .and()
                .subject(id.toString())
                . claim("tenantId", tenantId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshTokenExpiry)))
                .signWith(keyStore.getActiveKey())
                .compact();
    }

    /**
     * Access Token 전용 검증
     */
    public Claims parseAndValidateAccess(String token) {
        return parseAndValidate(token, TYPE_ACCESS);
    }

    /**
     * Refresh Token 전용 검증
     */
    public Claims parseAndValidateRefresh(String token) {
        return parseAndValidate(token, TYPE_REFRESH);
    }

    /**
     * Token 서명 검증
     * */
    private Claims parseAndValidate(String token, String expectedType) {
        // 1. kid 추출
        String kid = extractKid(token);

        // 2. kid 조회
        SecretKey key = keyStore.findKey(kid)
                .orElseThrow(() -> {
                    log.warn("JWT 검증 실패 - 알 수 없는 kid : {}", kid);
                    return new BusinessException(ErrorCode.INVALID_TOKEN);
            });
        // 3. 서명 검증 + claims 파싱
        Claims claims;
        try{
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        }catch (Exception e){
            log.warn("JWT 검증 실패 — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 4. type 클레임 검증
        String actualType = claims.get(CLAIM_TYPE, String.class);
        if(!expectedType.equals(actualType)){
            log.warn("JWT type 불일치 — expected: {}, actual: {}", expectedType, actualType);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }


        return claims;

    }

    /**
     * JWT 형식
     * header.payload.signature >> 첫번째 header를 추출해야함
     * generateAccessToken / generateRefreshToken 에서 header에 kid 설정함
     * */
    private String extractKid(String token) {
        try{
            String [] parts = token.split("\\.");
            if(parts.length != 3) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }

            String headerJson = new String (Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonNode header = OBJECT_MAPPER.readTree(headerJson);
            String kid = header.path("kid").asText(null);

            if(kid == null || kid.isBlank()) {
                log.warn("JWT 헤더에 kid 없음");
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }

            return kid;

        }catch (BusinessException e) {
            throw e;
        }catch (Exception e) {
            log.warn("JWT 헤더 파싱 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }


    }

}
