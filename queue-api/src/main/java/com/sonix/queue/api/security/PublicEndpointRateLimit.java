package com.sonix.queue.api.security;

/**
 *
 * SIGNUP(5, 60_000),  >> brute force 방지
 * LOGIN(10, 60_000),  >> brute force 방지
 * REFRESH(30, 60_000) >> sdk 호출 횟수 제한
 */

public enum PublicEndpointRateLimit {
    SIGNUP(5, 60_000),
    LOGIN(10, 60_000),
    REFRESH(30, 60_000);

    private final int limit;
    private final long windowSizeMillis;

    PublicEndpointRateLimit(int limit, long windowSizeMillis) {
        this.limit = limit;
        this.windowSizeMillis = windowSizeMillis;
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowSizeMillis() {
        return windowSizeMillis;
    }
}
