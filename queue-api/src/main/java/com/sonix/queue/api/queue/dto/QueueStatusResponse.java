package com.sonix.queue.api.queue.dto;

import com.sonix.queue.domain.queue.QueueBoard;

import java.util.Arrays;
import java.util.List;

/**
 * 큐 전광판 응답 (FRS §6.3 ①). <b>30만 명 전원에게 같은 바이트가 나간다.</b>
 *
 * <pre>
 * { "lastAdmittedSeq": 47,
 *   "pacing": [[50,2],[1000,5],[5000,10],[10000,15],[null,20]] }
 * </pre>
 *
 * <p><b>클라이언트가 하는 계산 (서버는 하지 않는다):</b>
 * <pre>
 *   wm    = max(직전 wm, lastAdmittedSeq)   ← 단조 clamp. WAS N대의 시점 차로 값이 작아질 수 있다
 *   rank  = max(0, mySeq − wm)
 *   간격  = pacing에서 rank 이하 첫 구간의 초 + 지터
 *   rank == 0 → 그때만 개인 엔드포인트로 admitToken 확인
 * </pre>
 *
 * <p>⚠️ <b>지터 규약은 아직 확정이 아니다.</b> §79 본문은 {@code ±20%}(대칭)라고 적었는데, 같은
 * 절의 Consequences는 이관되는 불변식을 <b>"지터는 등급 하한 위로만"</b>(비대칭)이라고 적었다.
 * 둘은 양립하지 않는다 — 대칭이면 실효 간격이 등급 하한 아래로 내려간다. 구 서버 구현
 * ({@code nextPollAfterSec})은 후자였다. <b>SDK 착수 전에 결론이 필요하다.</b>
 *
 * @param lastAdmittedSeq 마지막으로 admit된 seq. 아무도 입장 안 했으면 0
 * @param pacing          {@code [rank 상한, 간격 초]} 쌍의 목록. 마지막 항의 상한 {@code null}은 "그 이상 전부"
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
