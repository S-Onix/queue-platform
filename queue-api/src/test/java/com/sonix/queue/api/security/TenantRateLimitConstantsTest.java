package com.sonix.queue.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 테넌트 rate limit 상수의 계약을 잠근다 (§88 — Plan 등급제 제거).
 *
 * <p>🔴 <b>왜 필요한가.</b> 등급제를 걷어내면서 값이 {@code Plan} enum에서 상수 둘로 옮겨졌는데,
 * 그 상수를 <b>실제로 검증하는 테스트가 0건</b>이었다. 결함 주입으로 확인했다 —
 * {@code TENANT_CAPACITY}를 100,000 → 100으로 바꿔도, 비율을 깨뜨려도(refill만 1.67로)
 * <b>461건이 전부 초록</b>이었다. {@code RedisTokenBucketRateLimiterTest}는 리터럴을 쓰므로
 * 이 상수와 이어져 있지 않다(모듈이 달라 참조할 수도 없다 — infrastructure는 api를 모른다).
 *
 * <p>이 레포가 같은 형태로 이미 한 번 당했다: 기본값 회귀를 막으려 넣은 테스트가
 * {@code 999999999}·{@code 1ms}에도 통과했던 건(§드레인 용량 캐시). <b>"0이 아님"만 잠그면
 * 값은 안 잠긴다.</b>
 */
class TenantRateLimitConstantsTest {

    /**
     * 🔑 <b>잠그는 것은 절대값이 아니라 비율이다.</b> {@code capacity = refill × 60}은 §62에서 온
     * 계약이고 §88에서도 유지된다 — 티켓팅은 오픈 1분 안에 몰리므로 <b>1분치 burst</b>를 허용한다.
     *
     * <p>값 자체는 <b>일부러 잠그지 않는다</b>. 실제로 §89에서 100,000 → <b>50,000</b>으로 내렸고
     * (실측: 100,000에서는 3,000 rps 버스트를 <b>한 건도 막지 못했다</b>), 그때 이 테스트가
     * 막아서지 않았다 — 의도한 대로다. <b>막아야 하는 것은 "비율을 깨뜨린 채 한쪽만 바꾸는 것"</b>이다 —
     * 그러면 버킷 TTL({@code ceil(capacity/refill) + 60})과 {@code Retry-After}가 함께 어긋난다.
     */
    @Test
    @DisplayName("테넌트 버킷은 capacity = refill × 60 (1분치 burst) 비율을 지킨다")
    void capacityIsOneMinuteOfRefill() {
        assertThat(RateLimitFilter.TENANT_CAPACITY)
                .as("capacity(%d) = refill(%s) × 60 이어야 한다. 한쪽만 바꾸면 버킷 TTL과 "
                                + "Retry-After가 함께 어긋난다",
                        RateLimitFilter.TENANT_CAPACITY, RateLimitFilter.TENANT_REFILL_PER_SEC)
                .isCloseTo((int) Math.round(RateLimitFilter.TENANT_REFILL_PER_SEC * 60), within(1));
    }

    /**
     * 🪤 상수가 <b>양수</b>인지도 함께 본다. 0이나 음수가 들어가면 {@code token-bucket.lua}의
     * {@code ceil(capacity/refill)}이 0/무한이 되어 TTL이 무너지고, {@code Retry-After} 계산
     * ({@code ceil(1/refill)})이 0이 되어 클라이언트가 대기 없이 재시도한다.
     */
    @Test
    @DisplayName("상수는 양수다 — 0·음수는 TTL과 Retry-After를 동시에 무너뜨린다")
    void constantsArePositive() {
        assertThat(RateLimitFilter.TENANT_CAPACITY).isPositive();
        assertThat(RateLimitFilter.TENANT_REFILL_PER_SEC).isPositive();
    }
}
