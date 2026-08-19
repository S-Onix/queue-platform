package com.sonix.queue.domain.queue;

/**
 * {@code admit-by-admit} 키가 들고 있는 값 — admitToken이 가리키는 대상.
 *
 * <p>키의 존재 자체가 "60초 안에 admit됐다"의 증명(PX가 그 증명이다)이므로, verify는 이 값만으로
 * 답할 수 있고 DB를 읽지 않는다.
 *
 * @param tokenId    항상 있다
 * @param identifier 롤링 배포 중 남은 구 포맷 값(tokenId만 저장하던 시절)이면 {@code null}.
 *                   그때만 호출자가 DB로 신원을 찾는다
 */
public record AdmitRef(String tokenId, String identifier) {
}
