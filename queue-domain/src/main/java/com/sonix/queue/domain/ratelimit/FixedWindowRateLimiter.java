package com.sonix.queue.domain.ratelimit;

public interface FixedWindowRateLimiter {
    /**
     * key에 대해 요청 1건을 시도한다.
     *
     * @param key 제한 단위 (예: "rl:signup:ip:127.0.0.1")
     * @param limit 윈도우당 허용 요청 수
     * @param windowSizeMillis 윈도우 크기 (ms 단위, 예: 60000 = 1분)
     * @return 한도 이내라 허용되면 true, 한도 초과면 false
     */
    boolean tryAcquire(String key, int limit, long windowSizeMillis);
}
