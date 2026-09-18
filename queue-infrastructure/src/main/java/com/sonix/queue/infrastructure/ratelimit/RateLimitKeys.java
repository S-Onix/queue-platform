package com.sonix.queue.infrastructure.ratelimit;

public final class RateLimitKeys {
    private RateLimitKeys(){

    }

    public static String tenant(String tenantId) {
        return "rl:tenant:" + tenantId;
    }

    /**
     * 이유: 큐 상태 제어(생성·수정·삭제·pause·resume)와 API Key 관리 전용 버킷(§92).
     * 문제: 데이터 평면과 같은 지갑을 쓰면 enqueue 가 한도를 다 쓴 순간 {@code pause} 가 429 가 된다.
     * 원인: 🔑 <b>멈춰야 하는 순간이 곧 부하가 몰린 순간</b>이다(AWS 실측에서 실제로 났다).
     * 해결: <b>면제가 아니라 분리</b>다 — 이 호출들도 공짜가 아니라 한도는 남기되 <b>굶지 않게</b> 한다.
     */
    /**
     * 이유: 입장 처리(admit · verify · complete) 전용 버킷(§92).
     * 문제: <b>배출이 유입에 굶으면 안 된다</b> — admit 이 429 면 줄이 안 빠지고 폴링이 늘어
     *       더 나빠진다(<b>자기 강화 악순환</b>).
     * 해결: 셋을 <b>한 버킷에</b> 둔다 — admit 만 빼면 <b>돈은 청구되고 입장은 못 하는</b> 상태가 된다.
     * ⚠️ 나누면 테넌트 총량 한도가 올라간다(§89 의 절반을 되돌린다) — 받아들이는 근거는
     *    <b>배출량이 발급된 입장권 수에 묶여</b> 유입과 달리 무한정 늘 수 없다는 것이다.
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
     * 이유: Fixed Window 카운터의 <b>실제</b> 키(base + 윈도우 번호).
     * 문제: 예전에는 Lua 가 base 에 윈도우 번호를 이어붙여 {@code INCR} 했다.
     * 원인: <b>선언한 키와 실제로 만지는 키가 달라</b> Cluster 가 거부한다(인증 전 3종 전멸).
     *       🪤 Sentinel 엔 슬롯 개념이 없어 <b>로컬에서는 드러나지 않았다</b>.
     * 해결: Java 에서 조립해 넘긴다. 키 문자열은 <b>예전과 완전히 동일</b>하다 —
     *       Lua {@code math.floor} 와 자바 long 나눗셈이 음이 아닌 값에서 같은 결과를 낸다.
     */
   public static String fixedWindow(String baseKey, long nowMillis, long windowSizeMillis) {
        return baseKey + ":" + (nowMillis / windowSizeMillis);
    }
}
