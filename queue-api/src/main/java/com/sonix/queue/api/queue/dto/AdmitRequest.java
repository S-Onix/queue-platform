package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이유: Admit 요청(FRS §6.4). 300은 서버가 받아 주는 최대치이지 안전값이 아니다(Redis 비용은 토큰당 0.7μs, AWS 5차).
 * 문제: 입장권 TTL 60초는 발급 순간부터 흐른다. N장을 받아 초당 μ명씩 입장 처리하면 마지막 사람은 N/μ초 뒤라,
 *       60을 넘는 만큼이 만료된다(로컬 μ=2~4/s·N=300 에서 발급의 29%).
 * 해결: {@code count ≤ μ × 60}(μ = Tenant 가 verify 또는 complete 로 입장 처리를 끝내는 초당 인원). AWS 14차에서 예측이 실측과 97% 맞았다. §96-4
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
