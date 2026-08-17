package com.sonix.queue.infrastructure.queue;

/**
 * Queue Engine Redis 키 중앙 관리.
 *
 * <p>캐시 키가 아니므로 {@code cache.RedisKeyFactory}가 아니라 여기서 관리한다.
 * ({@code ratelimit.RateLimitKeys}와 같은 이유 — Lua 원자 연산으로 다루는 원본 데이터)
 *
 * <p><b>해시태그 {@code {queueId}} 필수:</b>
 * enqueue_bulk.lua는 waiting/seq/tokens 세 키를 함께 넘겨받는다. Redis Cluster는
 * {@code CRC16(key) % 16384}로 슬롯을 정하는데, 해시태그가 없으면
 * {@code queue:q_bts:waiting}(slot 7911)과 {@code queue:q_bts:seq}(slot 11273)가
 * 서로 다른 마스터에 저장된다. Lua Script는 노드 한 대에서만 실행되므로
 * {@code CROSSSLOT Keys in request don't hash to the same slot} 에러가 난다.
 *
 * <p>중괄호를 씌우면 슬롯 계산에 중괄호 <b>안쪽만</b> 쓰이므로, 두 키의 queueId가
 * 같은 이상 같은 슬롯(= 같은 마스터)에 저장되는 것이 수학적으로 보장된다.
 * Sentinel 환경에서는 슬롯 개념이 없어 무해하다.
 *
 * <p><b>⚠️ 이중 라우팅이 들어와도 해시태그는 {@code {queueId}} 그대로다.</b> 예전 주석은
 * "태그를 shard 단위({@code queue:{shard_X}:{queueId}:waiting})로 옮긴다"고 적혀 있었으나,
 * 그 안(키에 배정을 인코딩하는 방식)은 <b>기각됐다</b>(§75). 채택된 것은 안 (a″) —
 * 키는 그대로 두고 <b>클라이언트({@code RedisQueueEngine.route})가 어느 클러스터인지 정한다</b>.
 *
 * <p>태그를 shard로 옮기면 그 라우팅이 성립하지 않는다. {@code route()}는 소유자를 모를 때
 * <b>{@code EXISTS queue:&#123;queueId&#125;:seq}를 양쪽 클러스터에 물어</b> 답한 쪽을 소유자로
 * 삼는데, 키 이름에 shard가 들어가면 <b>질문을 만들려면 답을 이미 알아야</b> 하기 때문이다.
 * queueId는 테넌트에 노출된 영구 식별자라 나중에 형식을 바꿀 수도 없다.
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

    /** identifier -> tokenId 매핑 Hash (발급 원장 + EXISTS 재사용). */
    public static String tokens(String queueId) {
        return "queue:{" + queueId + "}:tokens";
    }

    /** inactive_ttl용 last-active ZSet (member=seq, score=timestamp ms). */
    public static String lastActive(String queueId) {
        return "queue:{" + queueId + "}:last-active";
    }
}
