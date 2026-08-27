package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * {@code admit-by-admit} 키가 들고 있는 값 — admitToken이 가리키는 대상.
 *
 * <p>키의 존재 자체가 "60초 안에 admit됐다"의 증명(PX가 그 증명이다)이므로, verify는 이 값만으로
 * <b>답과 완료 처리를 모두</b> 끝내고 DB를 한 번도 읽지 않는다.
 *
 * <p>{@code seq}·{@code issuedAt}이 함께 실리는 이유는 verify가 {@code COMPLETED} 이벤트를
 * 만들어야 하기 때문이다. 없으면 verify가 DB를 읽어야 하고, 그 읽기는 <b>master</b>로 간다.
 *
 * <p>🪤 구 주석은 "verify는 {@code @Transactional(readOnly = true)}라 Replica로 간다"였는데
 * <b>두 겹으로 거짓이다</b>: verify에는 트랜잭션 어노테이션이 <b>아예 없고</b>(Kafka 12초를
 * 커넥션 쥔 채 기다리지 않으려고 일부러 뺐다), 트랜잭션이 없으면 파생 쿼리는 master로 간다
 * (2026-08-27 라우팅 로그 실측).
 *
 * @param tokenId    항상 있다
 * @param seq        롤링 배포 중 남은 구 포맷이면 {@code -1}
 * @param issuedAt   롤링 배포 중 남은 구 포맷이면 {@code null}
 * @param identifier 구 포맷(tokenId만 저장하던 시절)이면 {@code null}.
 *                   그때만 호출자가 DB로 신원을 찾는다
 */
public record AdmitRef(String tokenId, long seq, Instant issuedAt, String identifier) {

    /** 이벤트를 만들 수 있는 값인가. 구 포맷이면 seq·issuedAt이 없어 만들 수 없다. */
    public boolean complete() {
        return identifier != null && issuedAt != null && seq >= 0;
    }
}
