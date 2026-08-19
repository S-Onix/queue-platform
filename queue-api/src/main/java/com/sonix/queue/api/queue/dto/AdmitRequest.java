package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admit 요청 (FRS §6.4).
 *
 * @param count     이번에 받을 인원. <b>상한 100</b> — Redis는 단일 스레드라 N이 크면 스크립트 하나가
 *                  master를 수십~100ms 잡고 그동안 폴링을 포함한 모든 명령이 밀린다.
 *                  올리는 건 하위호환이지만 내리는 건 파괴적 변경이라 시작값은
 *                  "견딜 수 있는 최대"가 아니라 "필요를 채우는 최소"다 (§80 ⑦).
 * @param requestId Tenant가 정하는 멱등 키. 같은 값으로 다시 부르면 대기열을 건드리지 않고
 *                  저장된 결과를 그대로 돌려준다(REPLAY).
 */
public record AdmitRequest(
        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 100, message = "count must be at most 100")
        int count,

        @NotBlank(message = "requestId is required")
        @Size(max = 100, message = "requestId must be at most 100 characters")
        String requestId
) {
}
