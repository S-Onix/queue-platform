package com.sonix.queue.domain.ratelimit;

/**
 * 이유: Rate Limit 아웃바운드 포트 — <b>Token Bucket</b> 계열(버스트를 허용하는 SLA 한도용).
 * 해결: 코어가 "제한할 수 있는 능력"을 요구하고 어댑터가 제공한다.
 * 🔧 구현체는 {@code InMemoryTokenBucketRateLimiter}(학습·단일 JVM) 와
 *    {@code RedisTokenBucketRateLimiter}(운영, Lua 원자 실행)다 — 예전 주석의 FixedWindow 이름은 거짓이었다.
 * 🪤 인증 전 엔드포인트는 이 포트가 아니라 {@code FixedWindowRateLimiter} 를 쓴다(§60·§61).
 *
 * @author sonix
 */
public interface RateLimiter {

    /**
     * key에 대해 요청 1건을 시도한다.
     *
     * @param key 제한 단위 (예: "rl:tenant:t-001")
     * @param capacity 양동이 크기 (burst 한도)
     * @param refillRatePerSecond 초당 회복 토큰
     * @return 허용되면 true, 한도 초과면 false
     */
    boolean tryAcquire(String key, int capacity, double refillRatePerSecond);
}
