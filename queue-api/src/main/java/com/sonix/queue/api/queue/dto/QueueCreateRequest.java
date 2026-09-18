package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 이유: 큐 생성 요청. 🔴 <b>숫자 셋에 하한과 상한이 필요하다</b> — 예전엔 검증이 이름뿐이었다.
 * 문제: {@code maxCapacity} 생략 시 0 이라 <b>첫 사람부터 영구 429</b>, {@code inactiveTtl} 이 0 이면
 *       <b>방금 폴링한 사람까지 전원</b> 회수되고 <b>과금은 그대로</b> 남는다.
 * 원인: 상한이 없으면 오타 하나로 가드가 사라지고 <b>같은 마스터의 다른 테넌트가 죽는다</b>.
 * 해결: 1~300,000 으로 묶는다 — 실측 <b>477 B/명</b>이라 30만이 136MB, 마스터 예산의 50% 안이다(§87).
 */
@Getter
@Setter
public class QueueCreateRequest {
    @NotBlank
    private String name;

    /** 생략 불가. {@code int}라 생략하면 0이 되고, 0은 아래 {@code @Min}에서 400으로 걸린다. */
    @Min(value = 1, message = "maxCapacity must be at least 1")
    @Max(value = 300_000, message = "maxCapacity must not exceed 300000")
    private int maxCapacity;

    /** null이면 기본 7200초. */
    @Min(value = 1, message = "waitingTtl must be at least 1 second")
    private Integer waitingTtl;

    /** null이면 기본 300초. */
    @Min(value = 1, message = "inactiveTtl must be at least 1 second")
    private Integer inactiveTtl;
}
