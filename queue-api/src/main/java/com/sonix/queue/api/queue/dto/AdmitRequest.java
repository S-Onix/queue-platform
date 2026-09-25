package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이유: Admit 요청(FRS §6.4). count 상한 300 은 Redis 부담 때문이 아니라 허용 상한이다(ZPOPMIN 토큰당 0.7μs, AWS 5차).
 * 문제: 입장권 TTL 60초는 발급 순간부터 흐른다 — 앉히는 속도보다 많이 받으면 꼬리가 앉기 전에 만료된다.
 * 해결: {@code count ≤ μ × 60초}(μ = 초당 앉히는 수). 로컬 μ=2~4/s·N=300 에서 발급의 29% 가 만료됐고,
 *       AWS 14차에서 이 식의 예측이 실측과 97% 맞았다. 값은 서버 상한이 아니라 자기 μ 로 정하라. §96-4
 *
 * @author sonix
 * @param count     이번에 받을 인원(1~300)
 * @param requestId Tenant가 정하는 멱등 키. 같은 값으로 다시 부르면 대기열을 건드리지 않고
 *                  저장된 결과를 그대로 돌려준다(REPLAY).
 */
public record AdmitRequest(
        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 300, message = "count must be at most 300")
        int count,

        @NotBlank(message = "requestId is required")
        @Size(max = 100, message = "requestId must be at most 100 characters")
        String requestId
) {
}
