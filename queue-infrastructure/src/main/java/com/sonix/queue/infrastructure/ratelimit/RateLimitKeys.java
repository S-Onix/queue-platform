package com.sonix.queue.infrastructure.ratelimit;

public final class RateLimitKeys {
    private RateLimitKeys(){

    }

    public static String tenant(String tenantId) {
        return "rl:tenant:" + tenantId;
    }

    /**
     * 큐 상태 제어(생성·수정·삭제·pause·resume)와 API Key 관리 전용 버킷.
     *
     * <p><b>데이터 평면과 지갑을 나눈다.</b> 둘이 같은 키를 쓰면 enqueue 가 한도를 다 쓴 순간
     * {@code pause} 가 429 가 되는데, <b>멈춰야 하는 순간이 곧 부하가 몰린 순간</b>이라
     * 비상 스위치가 정확히 필요할 때 안 눌린다(2026-09-16 AWS 실측에서 실제로 났다).
     *
     * <p>🔑 <b>면제가 아니라 분리다.</b> {@code delete} 는 Redis 키 여러 개를 지우고
     * {@code pause}·{@code resume} 은 DB 를 쓴다 — 공짜가 아니라서, 탈취된 JWT 로 반복 호출하면
     * 그 자체가 부하가 된다. 한도는 남기되 <b>굶지 않게</b> 한다.
     */
    /**
     * 입장 처리(admit · verify · complete) 전용 버킷.
     *
     * <p><b>배출이 유입에 굶으면 안 된다.</b> enqueue 와 같은 지갑을 쓰면, 유입이 한도를 다 쓴
     * 순간 {@code admit} 이 429 가 되고 줄이 안 빠진다. 줄이 안 빠지면 대기자가 쌓이고 폴링이
     * 늘어 상황이 더 나빠진다 — <b>자기 강화 악순환</b>이다.
     *
     * <p>🔑 셋을 <b>한 버킷에</b> 둔다. {@code admit} 만 빼면 입장권은 나갔는데 확인이 막혀
     * <b>돈은 청구되고 입장은 못 하는</b> 상태가 된다. 쪼개면 안 되는 단위다.
     *
     * <p>⚠️ 나누면 테넌트 총량 한도가 올라간다(§89 가 5만으로 내린 것의 절반을 되돌린다).
     * 받아들이는 근거는 <b>배출량이 발급된 입장권 수에 묶여 있다</b>는 것이다 — 유입과 달리
     * 무한정 늘 수 없다.
     */
    public static String tenantDrain(String tenantId) {
        return "rl:tenant:" + tenantId + ":drain";
    }

    public static String tenantControl(String tenantId) {
        return "rl:tenant:" + tenantId + ":control";
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
