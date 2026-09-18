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
 * 이유: JWT Secret Key 저장소 + Key Rotation. {@code jwt.active-kid} 와 {@code jwt.keys} 를 바인딩한다.
 * 문제: 키를 한 번에 갈면 <b>이미 발급된 토큰이 전부 무효</b>가 되어 전원이 재로그인한다.
 * 해결: 발급은 active key 하나로, 검증은 토큰 헤더의 {@code kid} 로 찾은 키로 한다 —
 *       옛 키를 검증용으로 남겨 <b>점진적 만료</b>가 되게 한다.
 * 🪤 회전 순서를 지켜라 — 새 키를 <b>먼저 배포</b>하고 그 뒤에 active 를 옮긴다(거꾸로면 401).
 *
 * @author sonix
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
        this.keyMap.clear(); // 캐시에 값이 남아 있을 수 있기 떄문에

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
