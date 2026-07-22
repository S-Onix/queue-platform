package com.sonix.queue.infrastructure.queue;

/**
 * Queue Engine Redis 키 중앙 관리.
 *
 * <p>캐시 키가 아니므로 {@code cache.RedisKeyFactory}가 아니라 여기서 관리한다.
 * ({@code ratelimit.RateLimitKeys}와 같은 이유 — Lua 원자 연산으로 다루는 원본 데이터)
 *
 * <p><b>해시태그 {@code {queueId}} 필수:</b>
 * enqueue_bulk.lua는 waiting/seq 두 키를 함께 넘겨받는다. Redis Cluster는
 * {@code CRC16(key) % 16384}로 슬롯을 정하는데, 해시태그가 없으면
 * {@code queue:q_bts:waiting}(slot 7911)과 {@code queue:q_bts:seq}(slot 11273)가
 * 서로 다른 마스터에 저장된다. Lua Script는 노드 한 대에서만 실행되므로
 * {@code CROSSSLOT Keys in request don't hash to the same slot} 에러가 난다.
 *
 * <p>중괄호를 씌우면 슬롯 계산에 중괄호 <b>안쪽만</b> 쓰이므로, 두 키의 queueId가
 * 같은 이상 같은 슬롯(= 같은 마스터)에 저장되는 것이 수학적으로 보장된다.
 * Sentinel 환경에서는 슬롯 개념이 없어 무해하다.
 *
 * <p>Sprint 12+ 이중 라우팅 도입 시 태그를 shard 단위로 옮긴다
 * ({@code queue:{shard_X}:{queueId}:waiting}).
 */
public final class QueueKeys {
    private QueueKeys(){
    }

    /** 대기열 ZSet (score = seq). */
    public static String waiting(String queueId) {
        return "queue:{" + queueId + "}:waiting";
    }

    /** 큐별 전역 순번 카운터 (INCR). */
    public static String seq(String queueId) {
        return "queue:{" + queueId + "}:seq";
    }
}
