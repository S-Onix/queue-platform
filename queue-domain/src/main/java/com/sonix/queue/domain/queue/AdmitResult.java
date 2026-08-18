package com.sonix.queue.domain.queue;

import java.util.List;

/**
 * Admit 실행 결과 (admit.lua 반환의 도메인 표현, FRS §6.4 / DECISIONS §80).
 *
 * @param replay  같은 requestId의 재시도라 저장된 payload를 그대로 돌려준 경우 true.
 *                <b>이때 records가 비어 있는 것은 정상이다</b> — 첫 호출이 0건이었으면
 *                이후 큐에 새 인원이 들어와도 계속 0건을 준다. 멱등의 정의가 그렇다
 *                ("같은 requestId엔 같은 답"). 새 인원을 받으려면 새 requestId를 쓴다.
 * @param records 이번에 admit된 대기자들. 요청한 count보다 적을 수 있다
 *                (대기열이 비었거나, tokens Hash 미스로 되돌려진 사람이 있을 때).
 */
public record AdmitResult(boolean replay, List<AdmitRecord> records) {

    /**
     * admit된 대기자 한 명.
     *
     * @param seq        대기 당시 순번. WAITING 복귀 시 score 복원에 쓰인다(§80).
     * @param admitToken 입장 자격 그 자체. verify가 이 값 하나로 통과하므로 UUIDv7이다(FRS §6.4).
     */
    public record AdmitRecord(String identifier, String tokenId, long seq, String admitToken) {
    }
}
