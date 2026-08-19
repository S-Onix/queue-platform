package com.sonix.queue.domain.queue;

import java.util.List;

/**
 * 큐 전광판 — {@code GET /api/v1/queues/&#123;queueId&#125;/status}의 응답 원본 (§79).
 * <b>30만 명 전원에게 같은 값</b>이다.
 *
 * <p>이름이 {@code QueueStatus}가 아닌 이유는 그 이름이 이미 큐의 <b>생명주기 상태</b>
 * (ACTIVE/PAUSED/DRAINING/DELETED)에 쓰이고 있어서다. 이쪽은 상태가 아니라 대기자에게 보여주는
 * 전광판 값이다.
 *
 * <p>구 {@code QueueSnapshot}(frontSeq/total)을 대체한다. 바뀐 것은 필드 이름이 아니라 성질이다:
 * <ul>
 *   <li>{@code frontSeq}는 <b>단조가 아니었다</b> — admitToken TTL 만료로 seq를 보존한 채 복귀하면
 *       (§36) 맨 앞 seq가 <b>작아져</b> 사용자 화면의 순번이 늘어난다. 전광판으로 쓸 수 없는 값이다.
 *       {@code lastAdmittedSeq}는 {@code admit.lua}가 현재값보다 클 때만 올려 후퇴하지 않는다.</li>
 *   <li>{@code total}({@code ZCARD})은 30만 ZSet 접근이었다. {@code lastAdmittedSeq}는 {@code GET}
 *       한 키 O(1)이고, 같은 해시태그라 {@code pacing}과 <b>한 번의 {@code MGET}</b>에 실린다.</li>
 * </ul>
 *
 * <p>⚠️ <b>rank는 여기서 계산하지 않는다.</b> {@code rank = mySeq − lastAdmittedSeq} 뺄셈 한 번은
 * 클라이언트가 한다. 서버가 대신해주면 응답이 사람마다 달라져 캐시·CDN 여지가 통째로 사라진다.
 *
 * <p>⚠️ <b>읽는 쪽도 단조를 지켜야 한다.</b> 세션 어피니티가 없어 같은 클라이언트의 연속 두 요청이
 * 서로 다른 WAS로 가면 받은 값이 작아질 수 있다. SDK가 {@code wm = max(wm, 받은값)}으로 clamp한다 —
 * {@code admit.lua}의 후퇴 방지와 한 세트다.
 *
 * <p>⚠️ <b>캐시가 아니라 원본이다.</b> Redis 유실 시 전광판이 0으로 돌아가 전원 순번이 폭증한다.
 * 복구원은 {@code tokens} 테이블의 {@code status IN (ADMIT_ISSUED, COMPLETED)} 최대 seq다 —
 * ADMIT_ISSUED만 세면 complete로 넘어갈수록 집합에서 빠져 watermark가 <b>후퇴</b>한다 (§71·§79).
 *
 * @param lastAdmittedSeq 마지막으로 admit된 seq. 아무도 입장하지 않았으면 {@code 0}이며 그게 맞는
 *                        값이다({@code rank = mySeq − 0 = mySeq}). 콜드 스타트 폴백이 따로 없는 이유다.
 * @param pacing          폴링 간격 사다리. Redis 오버라이드가 있으면 그 값, 없으면 {@link PacingTier#DEFAULT}
 */
public record QueueBoard(long lastAdmittedSeq, List<PacingTier> pacing) {
}
