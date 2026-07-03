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
}
