package com.sonix.queue.api.security;

import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * JWT Secret Key 저장소 + Key Rotation 지원
 *
 * application.yml 바인딩:
 *   jwt:
 *     active-kid: key-2026-01           # 발급용 active key ID
 *     keys:
 *       - kid: key-2026-01
 *         secret: <current secret>       # 발급 + 검증
 *       - kid: key-2025-12
 *         secret: <previous secret>      # 검증만 (이전 토큰 grace period)
 *
 * 동작:
 *   - 발급: getActiveKey()로 active key 사용
 *   - 검증: 토큰 헤더의 kid로 findKey() 후 그 키로 검증
 *   - active 전환 시점부터 새 토큰은 새 키로 발급
 *   - 옛 키는 검증용으로 유지 → 점진적 만료 (사용자 영향 최소)
 *
 * Rotation 절차:
 *   Day 0: keys 리스트에 새 키 추가 + 배포
 *   Day 1: active-kid를 새 키로 전환 + 배포
 *   Day 8+: keys에서 옛 키 제거 (Refresh Token TTL 후)
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtKeyStore {
    private String activeKid;
    private List<KeyConfig> keys;

    private final Map<String, SecretKey> keyMap = new LinkedHashMap<>();

    public void setActiveKid(String activeKid) {
        this.activeKid = activeKid;
    }

    public void setKeys(List<KeyConfig> keys) {
        this.keys = keys;
        this.keyMap.clear();

        if(keys == null) {
            return;
        }

        for(KeyConfig key : keys) {
            if(key.getKid() == null || key.getKid().isBlank()){
                continue;
            }
            if(key.getSecret() == null || key.getSecret().isBlank()){
                continue;
            }

            byte[] bytes = key.getSecret().getBytes(StandardCharsets.UTF_8);
            this.keyMap.put(key.getKid(), Keys.hmacShaKeyFor(bytes));
        }
    }

    /**
     * 발급 시 사용할 active key
     */
    public SecretKey getActiveKey() {
        SecretKey key = keyMap.get(activeKid);
        if (key == null) {
            throw new IllegalStateException(
                    "Active JWT key not found in key store. activeKid=" + activeKid
                            + ", registeredKids=" + keyMap.keySet()
            );
        }
        return key;
    }

    /**
     * 발급 시 JWT 헤더에 넣을 kid
     */
    public String getActiveKid() {
        return activeKid;
    }

    // ============================================================================
    // 검증용 — Key by kid
    // ============================================================================

    /**
     * 검증용 키 조회 (kid로)
     * Rotation 중인 모든 키 (active + previous) 지원
     */
    public Optional<SecretKey> findKey(String kid) {
        if (kid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(keyMap.get(kid));
    }

    /**
     * 등록된 모든 kid (디버깅, 모니터링용)
     */
    public List<String> getAllKids() {
        return List.copyOf(keyMap.keySet());
    }

    @Getter
    @Setter
    public static class KeyConfig {
        private String kid;
        private String secret;
    }

}
