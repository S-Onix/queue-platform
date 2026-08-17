package com.sonix.queue.infrastructure.ratelimit;

public final class RateLimitKeys {
    private RateLimitKeys(){

    }

    public static String tenant(String tenantId) {
        return "rl:tenant:" + tenantId;
    }

    public static String publicEndPoint(String action, String ip) {
        return "rl:" + action + ":ip" + ip;
    }

    public static String pollToken(String tokenId) {return "rl:poll:token:" + tokenId;}

    /**
     * Fixed Window 카운터의 <b>실제</b> 키 (base + 윈도우 번호).
     *
     * <p><b>왜 Java에서 조립하는가:</b> 예전에는 {@code fixed-window.lua}가 {@code KEYS[1]}에
     * 윈도우 번호를 이어붙여 {@code INCR}했다. 선언한 키와 실제로 만지는 키가 달라, Redis
     * Cluster가 {@code ERR Script attempted to access a non local key}로 거부한다
     * (인증 전 endpoint 3종 전멸). Sentinel에는 슬롯 개념이 없어 드러나지 않던 결함이다.
     *
     * <p>키 <b>문자열은 예전과 완전히 동일</b>하다 — Lua의 {@code math.floor(now/size)}와
     * 자바 long 나눗셈은 음이 아닌 값에서 같은 결과를 낸다. 기존 카운터·TTL 의미가 바뀌지 않는다.
     */
    public static String fixedWindow(String baseKey, long nowMillis, long windowSizeMillis) {
        return baseKey + ":" + (nowMillis / windowSizeMillis);
    }
}
