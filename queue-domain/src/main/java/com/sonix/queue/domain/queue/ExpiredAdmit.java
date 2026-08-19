package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * admitToken TTL이 만료돼 WAITING으로 되돌린 한 건 (§36 · §80).
 *
 * <p>{@code admitted} ZSet 멤버({@code "seq|identifier"})를 claim-Lua가 쪼갠 결과다.
 * 되돌리는 일({@code ZADD})은 Lua 안에서 이미 끝났고, 이 값은 <b>Kafka {@code RETURNED} 발행에
 * 필요한 재료</b>일 뿐이다.
 *
 * @param identifier Tenant가 정한 자유 문자열. {@code '|'}를 포함할 수 있어 Lua가 <b>첫 {@code '|'}</b>로만 쪼갠다
 * @param seq        <b>원래 순번.</b> 이 값 그대로 대기열에 되돌아가 우선순위가 보존된다
 * @param tokenId    {@code tokens} Hash 미스면 {@code null} — 그 경우 {@code RETURNED}를 발행할 수 없다
 * @param issuedAt   {@code tokens} 행의 파티션·멱등 키 절반. {@code tokenId}와 운명을 같이 한다
 */
public record ExpiredAdmit(String identifier, long seq, String tokenId, Instant issuedAt) {

    /** 발행에 필요한 두 값이 다 있는가. 없으면 호출자가 발행을 건너뛰고 로그만 남긴다. */
    public boolean publishable() {
        return tokenId != null && issuedAt != null;
    }
}
