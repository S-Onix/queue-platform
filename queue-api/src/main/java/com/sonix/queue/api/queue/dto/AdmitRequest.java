package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admit 요청 (FRS §6.4).
 *
 * @param count     이번에 받을 인원. <b>상한 300</b> — Redis는 단일 스레드라 N이 크면 스크립트 하나가
 *                  master를 수십~100ms 잡고 그동안 폴링을 포함한 모든 명령이 밀린다.
 *                  올리는 건 하위호환이지만 내리는 건 파괴적 변경이라 시작값은
 *                  "견딜 수 있는 최대"가 아니라 "필요를 채우는 최소"였다 (§80 ⑦).
 *                  <p>🔧 위 문단의 "수십~100ms"는 <b>실측의 1000배 과대평가</b>다(AWS 5차):
 *                  {@code ZPOPMIN} N=20 이 14.7μs, 토큰당 0.7μs 였다. <b>막는 것이 Redis 가
 *                  아니라는 것은 확정</b>이고, 그래서 2026-09-22 에 100 → 300 으로 올렸다.
 *                  <p>🔴 다만 <b>300 자체는 아직 부하 중에 안 재 봤다.</b> 진짜 상한은 Redis 가
 *                  아니라 <b>지금 비어 있는 좌석 수</b>다 — admitToken TTL 60초는 발급 순간부터
 *                  흐르므로, 앉힐 수 없는 사람에게 뿌리면 그대로 만료된다(5만 판에서
 *                  {@code issuedButUnclaimed} 49,920 이 그 모습이었다). AWS 500만 판에서
 *                  admit p99 · 폴링 지연 · 만료 토큰 수를 같이 보고, 나빠지면 되돌린다.
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
