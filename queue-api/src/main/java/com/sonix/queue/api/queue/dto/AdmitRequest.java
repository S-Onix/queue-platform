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
 *                  <p>🔴 <b>진짜 상한은 Redis 가 아니라 "당신이 초당 몇 명을 앉히는가"다.</b>
 *                  admitToken TTL 60초는 <b>발급 순간부터</b> 흐른다 — 앉히는 속도보다 많이 받으면
 *                  꼬리는 앉기 전에 만료된다(5만 판의 {@code issuedButUnclaimed} 49,920 이 그 모습).
 *                  <p>🔑 <b>산정식(Little, L = λW)</b>:
 *                  <pre>  count ≤ μ × 60초 × 안전계수     (μ = 초당 앉히는 사람 수)</pre>
 *                  받은 N 장을 한 명씩 앉히면 꼬리는 {@code N/μ} 초 뒤에 앉는다. 그 값이 60을
 *                  넘는 만큼이 그대로 만료분이다.
 *                  <p>실측(2026-09-23 로컬, N=300 · μ=2~4/s → 꼬리 75~150초): 발급의 <b>29%</b>가
 *                  TTL 을 넘겨 앉았고 verify·complete 가 <b>404 로 373건</b> 거절됐다. 같은 하니스가
 *                  페이싱 없이 돌던 판에서는 만료가 발급의 2.0% 뿐이라 <b>이 결함이 보이지 않았다</b>.
 *                  <p>🪤 그래서 <b>300 은 "안전한 값"이 아니라 "허용 상한"이다.</b> μ=3/s 인 Tenant 의
 *                  안전값은 186 이고, μ=9/s 면 564 까지 가능하다(상한 300 에 걸린다).
 *                  값을 정할 때 서버 상한이 아니라 <b>자기 μ</b>를 봐라.
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
