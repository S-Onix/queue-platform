package com.sonix.queue.infrastructure.cache;


/**
 * 이유: Redis <b>캐시</b> 키 중앙 관리. Enum 이 아니라 static 메서드다(가변인수 타입 안전성, §7).
 * 🔑 Rate Limit·큐 상태 키는 여기 없다 — 원자 연산으로 다루는 <b>원본</b>이라 각자 클래스가 있다.
 * 🔴 {@link #refreshToken(String)} 은 <b>호출자가 0 이다</b>(2026-09-19 전수) — Refresh 는 DB 조회로
 *    충분해 캐시를 붙이지 않았다. 쓸 곳이 생기기 전에는 §4-1 대상이니 <b>쓰거나 지워라</b>.
 * 실제로 쓰이는 것은 {@link #apiKey(String)}(인증 핫패스)와 {@link #tenant(Long)}(Rate Limit) 둘이다.
 *
 * @author sonix
 */
public final class RedisKeyFactory {
    private RedisKeyFactory(){}

    public static String apiKey(String keyHash) {
        return "apikey:" + keyHash;
    }

    /**
     * Tenant 캐시 키. <b>PK 기준</b>이다 — 캐시를 쓰는 Rate Limit 경로에서 JWT·API-Key 두
     * 인증 방식이 공통으로 확보하는 식별자가 PK뿐이기 때문이다.
     */
    public static String tenant(Long id) {
        return "tenant:" + id;
    }

    public static String refreshToken(String tokenHash) {
        return "refresh-token:" + tokenHash;
    }
}
