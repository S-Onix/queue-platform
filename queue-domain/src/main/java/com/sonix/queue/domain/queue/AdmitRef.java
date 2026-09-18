package com.sonix.queue.domain.queue;

import java.time.Instant;

/**
 * 이유: {@code admit-by-admit} 키가 들고 있는 값 — admitToken 이 가리키는 대상.
 * 원인: 키의 존재 자체가 "60초 안에 admit 됐다"의 증명이다(PX 가 그 증명이다).
 * 해결: verify 는 값만으로 답과 완료 처리를 끝내고 <b>DB 를 한 번도 읽지 않는다</b>.
 *       {@code seq}·{@code issuedAt} 을 함께 싣는 것은 COMPLETED 이벤트를 만들기 위해서다.
 * 🪤 verify 에 트랜잭션 어노테이션이 <b>없다</b> — Kafka 를 커넥션 쥔 채 기다리지 않으려고 뺐다(§4-3).
 *
 * @author sonix
 * @param tokenId    항상 있다
 * @param seq        롤링 배포 중 남은 구 포맷이면 {@code -1}
 * @param issuedAt   롤링 배포 중 남은 구 포맷이면 {@code null}
 * @param identifier 구 포맷(tokenId 만 저장하던 시절)이면 {@code null}. 그때만 호출자가 DB 로 찾는다
 */
public record AdmitRef(String tokenId, long seq, Instant issuedAt, String identifier) {

    /** 이벤트를 만들 수 있는 값인가. 구 포맷이면 seq·issuedAt이 없어 만들 수 없다. */
    public boolean complete() {
        return identifier != null && issuedAt != null && seq >= 0;
    }
}
