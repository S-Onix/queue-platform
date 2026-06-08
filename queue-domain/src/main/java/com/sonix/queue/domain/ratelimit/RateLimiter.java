package com.sonix.queue.domain.ratelimit;

/**
 * Rate Limit 아웃바운드 포트.
 *
 * <p>애플리케이션 코어가 "요청을 제한할 수 있는 능력"을 요구하고,
 * 어댑터(인메모리 / Redis-Lua)가 그 능력을 제공한다.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code InMemoryFixedWindowRateLimiter} — 학습/비교 전용 (단일 JVM)</li>
 *   <li>{@code RedisFixedWindowRateLimiter} — 운영용 (Step 3, Lua Script 원자 실행)</li>
 * </ul>
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
