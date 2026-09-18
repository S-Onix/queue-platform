package com.sonix.queue.domain.queue;

import java.util.List;

/**
 * 이유: 큐 전광판 — {@code GET /status} 의 응답 원본(§79). <b>30만 명 전원에게 같은 값</b>이다.
 * 문제: 구 {@code frontSeq} 는 <b>단조가 아니었다</b> — admitToken TTL 만료로 맨 앞 seq 가
 *       작아지면 사용자 화면의 순번이 거꾸로 늘어난다. {@code total}(ZCARD)은 30만 ZSet 접근이었다.
 * 해결: {@code lastAdmittedSeq} 는 클 때만 올라가 후퇴하지 않고, 한 키 O(1) 이라 MGET 한 번이다.
 * ⚠️ <b>캐시가 아니라 원본이다</b> — Redis 유실 시 0으로 돌아가 전원 순번이 폭증한다(복구는 §71·§79).
 *
 * @author sonix
 * @param lastAdmittedSeq 마지막으로 admit된 seq. 아무도 입장하지 않았으면 {@code 0}이며 그게 맞는
 *                        값이다({@code rank = mySeq − 0 = mySeq}). 콜드 스타트 폴백이 따로 없는 이유다.
 * @param pacing          폴링 간격 사다리. Redis 오버라이드가 있으면 그 값, 없으면 {@link PacingTier#DEFAULT}
 */
public record QueueBoard(long lastAdmittedSeq, List<PacingTier> pacing) {
}
