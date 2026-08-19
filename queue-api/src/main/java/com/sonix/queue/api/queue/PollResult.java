package com.sonix.queue.api.queue;

/**
 * 개인 폴링 결과 — <b>개인화된 값만 남긴다</b> (§79).
 *
 * <p>구 필드 {@code frontSeq}·{@code total}·{@code nextPollAfterSec}는 전부 <b>큐 단위 공유값</b>인데
 * 개인 응답에 실려 있었다. 그래서 30만 명분 응답이 전부 달라 캐시가 불가능했고, 경로 전체가
 * {@code EVAL}(write)이라 master에 15k rps가 고정됐다. 셋은 {@code /status}로 옮겨졌다.
 */
public record PollResult(boolean ready, String admitToken) {

}
