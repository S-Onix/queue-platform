package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * admitToken TTL이 만료돼 회수한 한 건 (§36 · §80).
 *
 * <p>{@code admitted} ZSet 멤버({@code "seq|identifier"})를 claim-Lua가 쪼갠 결과다.
 * <b>대기열로 되돌리지 않는다</b>(§36) — 중복 게이트 해제({@code HDEL tokens})는 Lua 안에서
 * 이미 끝났고, 이 값은 <b>Kafka {@code EXPIRED} 발행에 필요한 재료</b>일 뿐이다.
 *
 * @param identifier Tenant가 정한 자유 문자열. {@code '|'}를 포함할 수 있어 Lua가 <b>첫 {@code '|'}</b>로만 쪼갠다
 * @param seq        만료 시점의 순번. 로그·관측용이다 — 되돌리지 않으므로 복원에 쓰이지 않는다
 * @param tokenId    {@code tokens} Hash 미스면 {@code null} — 그 경우 {@code EXPIRED}를 발행할 수 없다
 * @param issuedAt   {@code tokens} 행의 파티션·멱등 키 절반. {@code tokenId}와 운명을 같이 한다
 */
public record ExpiredAdmit(String identifier, long seq, String tokenId, Instant issuedAt) {

    /** 발행에 필요한 두 값이 다 있는가. 없으면 호출자가 발행을 건너뛰고 로그만 남긴다. */
    public boolean publishable() {
        return tokenId != null && issuedAt != null;
    }
}
