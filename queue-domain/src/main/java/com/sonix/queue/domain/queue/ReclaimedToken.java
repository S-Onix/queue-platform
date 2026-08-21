package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * 회수 배치가 큐에서 빼낸 한 건. <b>두 경로가 공유한다</b>.
 *
 * <ul>
 *   <li><b>admitToken TTL 만료</b>(§36 · §80) — {@code admitted} ZSet, member = {@code "seq|identifier"}</li>
 *   <li><b>inactiveTtl 초과</b>(§82) — {@code last-active} ZSet, member = {@code seq}.
 *       identifier는 {@code waiting}에서 역산한다</li>
 * </ul>
 *
 * <p>입력 자료구조는 다르지만 <b>결과 모양이 같다</b> — 어느 쪽이든 Redis 회수는 claim-Lua 안에서
 * 이미 끝났고, 이 값은 <b>Kafka {@code EXPIRED} 발행에 필요한 재료</b>일 뿐이다.
 * 어느 경로도 대기열로 되돌리지 않는다(§36).
 *
 * @param identifier Tenant가 정한 자유 문자열. {@code '|'}를 포함할 수 있어 Lua가 <b>첫 {@code '|'}</b>로만 쪼갠다
 * @param seq        회수 시점의 순번. 로그·관측용이다 — 되돌리지 않으므로 복원에 쓰이지 않는다
 * @param tokenId    {@code tokens} Hash 미스면 {@code null} — 그 경우 {@code EXPIRED}를 발행할 수 없다
 * @param issuedAt   {@code tokens} 행의 파티션·멱등 키 절반. {@code tokenId}와 운명을 같이 한다
 */
public record ReclaimedToken(String identifier, long seq, String tokenId, Instant issuedAt) {

    /** 발행에 필요한 두 값이 다 있는가. 없으면 호출자가 발행을 건너뛰고 로그만 남긴다. */
    public boolean publishable() {
        return tokenId != null && issuedAt != null;
    }
}
