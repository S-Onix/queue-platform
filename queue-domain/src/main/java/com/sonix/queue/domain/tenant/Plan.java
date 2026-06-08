package com.sonix.queue.domain.tenant;
/**
 * Tenant의 SaaS 구독 플랜
 * 소모량으로 plan을 계획하다가 구독형으로 전환 이유
 *   - rate limiter 도입에 따른 capacity 및 token 회복수에 대한 차이점을 주기 위함
 *
 * 비율 : capacity = refillRatePerSecond ㅌ 60
 *       ex) bucket 용량이 1000인 경우  1000/60(초당 토큰 회복 개수) = 16.7
 * */
public enum Plan {
    FREE(100, 1.67),
    STARTER(1_000, 16.67),
    PRO(10_000, 166.67),
    ENTERPRISE(100_000, 1666.67);

    private final int capacity;
    private final double refillRatePerSecond;

    Plan(int capacity, double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    /**
     * DB 저장용 코드, TenantStatus 참조
     * */
    public int getCode(){
        return ordinal();
    }

    public static Plan fromCode(int code){
        Plan [] plans = values();
        if(code < 0 || code >= plans.length)
            throw new IllegalArgumentException("Unknown plan code.");
        return plans[code];
    }
}
