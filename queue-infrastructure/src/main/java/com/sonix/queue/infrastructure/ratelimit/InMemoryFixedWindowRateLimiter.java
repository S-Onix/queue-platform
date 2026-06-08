package com.sonix.queue.infrastructure.ratelimit;

import com.sonix.queue.domain.ratelimit.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed Window 알고리즘의 인메모리 구현 (⚠️ 학습/비교 전용).
 *
 * <p><b>운영 사용 금지</b>: 카운터 상태가 단일 JVM 메모리에만 존재한다.
 * API 서버를 N대로 수평 확장하면 서버마다 카운터가 따로 돌아
 * 전체 통과량이 {@code N × limit} 이 된다 (CLAUDE.md: ConcurrentHashMap을
 * 분산 상태 저장에 쓰지 말 것). 분산 환경에서는 Step 3의
 * {@code RedisFixedWindowRateLimiter}(Lua Script)를 사용한다.
 *
 * <p><b>학습 포인트</b>: read-modify-write를 {@link ConcurrentHashMap#compute}로
 * 원자화하는 것이, Step 3에서 Lua Script가 Redis에서 보장해주는 원자성과
 * 동일한 사상이다. 무대(JVM vs Redis)만 다르다.
 */
public class InMemoryFixedWindowRateLimiter implements RateLimiter {

    private final int limit;          // 한 창에서 허용할 최대 요청 수 (예: 100)
    private final long windowMillis;  // 창 크기 (ms) (예: 1000 = 1초)

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryFixedWindowRateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /** 한 창의 상태: 창 번호 + 그 창에서 누적된 요청 수 */
    private record Window(long windowNo, int count) {}

    @Override
    public boolean tryAcquire(String key, int capacity, double refillRatePerSecond) {
        // 1) 시각을 창 크기로 나눠 '창 번호'를 만든다 (정수 나눗셈 → 자동 구간화)
        long currentWindowNo = System.currentTimeMillis() / windowMillis;

        // 2) read-modify-write를 compute()로 원자화 (Step 3에서 Lua가 Redis에서 할 일과 동일 사상)
        Window updated = windows.compute(key, (k, prev) ->
            (prev == null || prev.windowNo() != currentWindowNo)
                    ? new Window(currentWindowNo, 1)                   // 새 창 → 카운트 리셋
                    : new Window(currentWindowNo, prev.count() + 1)   // 같은 창 → +1
        );

        // 3) 갱신된 카운트가 한도 이내면 허용
        return updated.count() <= limit;
    }
}
