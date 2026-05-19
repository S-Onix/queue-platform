package com.sonix.queue.domain.auth;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
public class RefreshToken {
    private final Long id;
    private final Long tenantId;
    private final String tokenHash;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;
    private LocalDateTime revokedAt;

    private RefreshToken(Long id, Long tenantId
            , String tokenHash, LocalDateTime issuedAt
            , LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public static RefreshToken create(String rawToken, Long tenantId, Duration ttl) {
        Objects.requireNonNull(rawToken, "rawToken은 필수");
        Objects.requireNonNull(tenantId, "tenantId는 필수");
        Objects.requireNonNull(ttl, "ttl은 필수");

        LocalDateTime now = LocalDateTime.now();
        return new RefreshToken(
                null,
                tenantId,
                sha256(rawToken),
                now,
                now.plus(ttl),
                null            // 발급 직후엔 폐기 안 됨
        );
    }

    /**
     * SHA-256 hex 변환 (정적 유틸)
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * DB에서 복원
     */
    public static RefreshToken reconstruct(Long id, Long tenantId, String tokenHash,
                                           LocalDateTime issuedAt, LocalDateTime expiresAt,
                                           LocalDateTime revokedAt) {
        return new RefreshToken(id, tenantId, tokenHash, issuedAt, expiresAt, revokedAt);
    }

    /**
     * 토큰 폐기 (Token Rotation 시)
     */
    public void revoke() {
        if (this.revokedAt != null) {
            return;       // 이미 폐기됨 (멱등)
        }
        this.revokedAt = LocalDateTime.now();
    }

    /**
     * 만료 여부
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 폐기 여부
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * 사용 가능 여부 (만료 안 됨 + 폐기 안 됨)
     */
    public boolean isUsable() {
        return !isExpired() && !isRevoked();
    }

    /**
     * 주어진 원본 토큰과 일치하는가?
     */
    public boolean matches(String rawToken) {
        return tokenHash.equals(sha256(rawToken));
    }
}
