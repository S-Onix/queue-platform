package com.sonix.queue.api.queue.dto;

import com.sonix.queue.domain.queue.QueueBoard;

import java.util.Arrays;
import java.util.List;

/**
 * 이유: 전광판 응답(FRS §6.3 ①). <b>30만 명 전원에게 같은 바이트가 나간다.</b>
 * 해결: 서버는 {@code lastAdmittedSeq} 와 {@code pacing} 표만 준다 — rank 도 간격도 계산하지 않는다.
 * 🔴 클라이언트는 {@code wm = max(직전 wm, lastAdmittedSeq)} 로 <b>단조 clamp</b> 하고
 *    {@code rank = max(0, mySeq − wm)} 를 쓴다 — clamp 를 빼면 <b>순번이 거꾸로 간다</b>(§79).
 * 🪤 필드 단위 명세와 예시는 {@code doc/API.md} 가 정본이다.
 *
 * @author sonix
 */
public record QueueStatusResponse(long lastAdmittedSeq, List<List<Long>> pacing) {

    public static QueueStatusResponse from(QueueBoard status) {
        // Arrays.asList를 쓰는 이유: 마지막 구간의 상한이 null인데 List.of는 null을 거부한다.
        List<List<Long>> pacing = status.pacing().stream()
                .map(tier -> Arrays.asList(tier.maxRank(), (long) tier.intervalSec()))
                .toList();
        return new QueueStatusResponse(status.lastAdmittedSeq(), pacing);
    }
}
