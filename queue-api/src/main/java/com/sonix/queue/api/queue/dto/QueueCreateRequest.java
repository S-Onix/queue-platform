package com.sonix.queue.api.queue.dto;

import jakarta.validation.constraints.Max;
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
 * <p>🔴 <b>{@code maxCapacity}에는 상한이 있다 (2026-09-03).</b> 예전엔 "Redis 노드 메모리 숫자가
 * 아직 없다"는 이유로 두지 않았는데, <b>그 근거가 소멸했다</b> — 대기자 1명당 Redis 사용량을
 * 실측했다(300,000명 적재 시 <b>477 B/명</b>, 30만 = 136MB. 200,000명 판에서는 447 B/명).
 *
 * <p>안 두면 무엇이 깨지는가: {@code maxCapacity}는 {@code enqueue_bulk.lua}의
 * {@code currentSize >= maxCapacity} 가드를 정하는 값이라, 오타 하나(0을 더 붙임)로 그 가드가
 * 사실상 사라진다. 한 큐의 키는 해시태그로 마스터 <b>한 대</b>에 못 박혀 있고({@code noeviction}),
 * 그 마스터가 차면 <b>같은 마스터에 사는 다른 테넌트의 enqueue가 죽는다</b>.
 * 결함 주입으로 재현했다 — 피해 큐의 쓰기는 {@code OOM command not allowed},
 * 읽기(폴링)는 정상. 대기자에게는 "줄은 보이는데 못 들어가는" 상태로 나타난다.
 *
 * <p>값 300,000은 마스터 예산(4GB의 50% = 약 450만 명)이 아니라 <b>문서화된 필요</b>(FRS의
 * "큐당 최대 30만 명")를 따른다. 물리 상한까지는 7.8배 여유가 있지만, 올리는 것은 하위호환이고
 * 내리는 것은 파괴적 변경이라 "필요를 채우는 최소"에서 시작한다(§80 ⑦).
 *
 * <p>⚠️ <b>이 상한은 메모리 보증이 아니라 blast radius 상한이다.</b> ① 막는 것은
 * {@code ZCARD waiting}뿐이라 admit된 사람의 {@code tokens} Hash 항목은 상한 밖에서 남는다.
 * ② <b>한 마스터의 합계는 이 값이 못 막는다</b> — 그건 {@code QueueService}의
 * 테넌트당 큐 개수 상한이 맡는다. 둘은 한 덩어리다.
 *
 * <p>TTL 상한은 여전히 두지 않는다 — 과도하게 크면 회수가 늦어질 뿐 <b>깨지지는 않는다</b>.
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
    @Max(value = 300_000, message = "maxCapacity must not exceed 300000")
    private int maxCapacity;

    /** null이면 기본 7200초. */
    @Min(value = 1, message = "waitingTtl must be at least 1 second")
    private Integer waitingTtl;

    /** null이면 기본 300초. */
    @Min(value = 1, message = "inactiveTtl must be at least 1 second")
    private Integer inactiveTtl;
}
