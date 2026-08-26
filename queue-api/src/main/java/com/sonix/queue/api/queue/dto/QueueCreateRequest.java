package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 큐 생성 요청.
 *
 * <p>🔴 <b>숫자 셋에 하한이 필요하다.</b> 예전엔 {@code @NotBlank name}만 있었고 나머지는 무제약이라,
 * 잘못된 값이 <b>큐 생성 200으로 통과한 뒤</b> 런타임에 드러났다. Tenant는 왜 안 되는지 알 방법이 없다.
 *
 * <ul>
 *   <li>{@code maxCapacity}를 <b>생략하면 {@code int} 기본값 0</b>이 되고,
 *       {@code enqueue_bulk.lua}의 {@code if currentSize >= maxCapacity}가 {@code 0 >= 0}으로 참이라
 *       <b>그 큐는 첫 사람부터 영구 429(QUEUE_FULL)</b>가 된다.</li>
 *   <li>{@code inactiveTtl}이 0이면 회수 배치의 cutoff가 {@code now - 0}이라
 *       {@code inactive_expire.lua}가 <b>방금 폴링한 사람까지 전원</b>을 집는다.
 *       10초 뒤 큐가 통째로 비고, {@code tokens} 행은 남아 <b>과금은 그대로 발생</b>한다(§84).</li>
 *   <li>{@code waitingTtl}이 0·음수면 cutoff가 미래라 결과가 같다.</li>
 * </ul>
 *
 * <p><b>상한은 두지 않는다.</b> {@code maxCapacity} 상한은 Redis 노드 메모리와 묶여 있고(§75 D25의
 * 50% 임계), 그 값이 아직 정해지지 않았다. 근거 없는 숫자를 지금 박으면 나중에 두 번 정한다.
 * TTL 상한도 마찬가지다 — 과도하게 크면 회수가 늦어질 뿐 <b>깨지지는 않는다</b>.
 *
 * <p>TTL 둘은 {@code Integer}라 <b>null이면 기본값</b>(waitingTtl 7200 · inactiveTtl 300)이 적용된다
 * ({@code Queue.create}). {@code @Min}은 null을 통과시키므로 그 계약이 유지된다.
 */
@Getter
@Setter
public class QueueCreateRequest {
    @NotBlank
    private String name;

    /** 생략 불가. {@code int}라 생략하면 0이 되고, 0은 아래 {@code @Min}에서 400으로 걸린다. */
    @Min(value = 1, message = "maxCapacity must be at least 1")
    private int maxCapacity;

    /** null이면 기본 7200초. */
    @Min(value = 1, message = "waitingTtl must be at least 1 second")
    private Integer waitingTtl;

    /** null이면 기본 300초. */
    @Min(value = 1, message = "inactiveTtl must be at least 1 second")
    private Integer inactiveTtl;
}
