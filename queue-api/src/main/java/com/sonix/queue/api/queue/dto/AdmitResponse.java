package com.sonix.queue.api.queue.dto;

import com.sonix.queue.domain.queue.AdmitResult;

import java.util.List;

/**
 * Admit 응답 (FRS §6.4).
 *
 * <p><b>200이 보장하는 것</b>: 이 사람들은 대기열에서 빠졌고 admitToken을 쥐었다(Redis의 사실).
 * <b>보장하지 않는 것</b>: {@code tokens.status}가 이미 1이다 — 그건 Kafka 소비 후에 그렇게 된다.
 *
 * <p>요청한 {@code count}보다 적을 수 있다(대기열이 비었거나, tokens Hash 미스로 되돌려진 사람).
 */
public record AdmitResponse(List<Admitted> admitted) {

    public record Admitted(String tokenId, String identifier, long seq, String admitToken) {
    }

    public static AdmitResponse from(AdmitResult result) {
        return new AdmitResponse(result.records().stream()
                .map(r -> new Admitted(r.tokenId(), r.identifier(), r.seq(), r.admitToken()))
                .toList());
    }
}
