package com.sonix.queue.infrastructure.cache;


/**
 * Redis 캐시 키 중앙 관리.
 *
 * <p>주의: Rate Limit 키는 여기 없음.
 * Rate Limit은 Redis 원자 연산(INCR, Lua Script)으로 캐시가 아니므로
 * {@code infrastructure.ratelimit} 패키지에서 별도 관리 예정.
 *
 * <p>설계 결정 (CLAUDE.md §7):
 * <ul>
 *   <li>Enum 대신 static 메서드 — 가변인수 타입 안전성</li>
 *   <li>키 파라미터가 메서드 시그니처로 문서화됨</li>
 *   <li>인스턴스화 방지 (private 생성자)</li>
 * </ul>
 *
 * <p>Sprint 5-D 캐시 대상:
 * <ul>
 *   <li>Tenant — Rate Limiter Plan 조회 부담 해소 (즉시 실효)</li>
 *   <li>Refresh Token — 재사용 감지 성능 향상</li>
 *   <li>ApiKey — Sprint 6 ApiKey 인증 도입 대비</li>
 * </ul>
 */
public final class RedisKeyFactory {
    private RedisKeyFactory(){}

    public static String apiKey(String keyHash) {
        return "apikey:" + keyHash;
    }

    public static String tenant(String tenantId) {
        return "tenant:" + tenantId;
    }

    public static String refreshToken(String tokenHash) {
        return "refresh-token:" + tokenHash;
    }
}
