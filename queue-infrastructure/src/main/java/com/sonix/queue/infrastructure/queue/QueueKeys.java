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

    /**
     * identifier -> {@code "tokenId|issuedAt"} 매핑 Hash (발급 원장 + EXISTS 재사용).
     *
     * <p>🔴 <b>이 Hash의 필드 존재 = 중복 게이트다.</b> {@code enqueue_bulk.lua}가 {@code HSETNX}로
     * 신규/기존을 가른다. {@code waiting} ZSet은 게이트가 아니다 — admit되면 거기서 빠지는데
     * 그 사람은 아직 큐를 떠난 것이 아니라서, waiting으로 판정하면 admit된 사람의 재-enqueue가
     * 새 tokenId·새 seq를 받는다(폴링 404 · {@code billing_snapshots}의 {@code COUNT(*)} 과금
     * 중복 · {@code status=1} 고아 행).
     *
     * <p>따라서 <b>사람을 큐에서 빼는 경로는 반드시 이 필드를 {@code HDEL}한다.</b> 현재 그 경로는 <b>넷</b>이다 —
     * {@code cleanup_completed.lua}(complete) · {@code admit_expire.lua}(§36) ·
     * {@code inactive_expire.lua}(§82) · {@code waiting_expire.lua}(§82).
     * 새 경로(예: cancel)를 만들 때도 같은 규칙이다 — 안 지우면 그 사람은 영영 재입장하지 못하고,
     * 먼저 지우면 아직 큐에 있는 사람이 폴링에서 404가 된다. 넷 모두 HDEL을 <b>마지막</b>에 둔다.
     *
     * <p>🔴 <b>이 필드의 키는 identifier(사람)인데 값은 tokenId(회차)다. 지울 때는 값을 봐야 한다.</b>
     * identifier는 회차 간에 재사용되므로(같은 사용자 = 같은 UUIDv7), 회차 정보 없이 identifier만
     * 보고 지우면 <b>다른 회차의 게이트를 지운다</b>. 네 경로가 그 문제를 각각 이렇게 피한다:
     * {@code cleanup_completed.lua}는 <b>{@code HGET}으로 tokenId를 대조</b>하고, 나머지 셋은
     * identifier를 <b>지금 Redis에서</b> 얻는다({@code admit_expire}는 {@code admitted} member
     * {@code "seq|identifier"}에서, {@code inactive_expire}는 {@code last-active}의 seq로 waiting을
     * 역산해서, {@code waiting_expire}는 waiting 스냅샷에서 직접). <b>바깥에서 들고 온 identifier를
     * 대조 없이 쓰는 경로를 새로 만들지 마라</b> — 그것이 정확히 "늦은 complete가 재-enqueue한
     * 사용자를 축출"한 결함이었다.
     */
    public static String tokens(String queueId) {
        return "queue:{" + queueId + "}:tokens";
    }

    /** inactive_ttl용 last-active ZSet (member=seq, score=timestamp ms). */
    public static String lastActive(String queueId) {
        return "queue:{" + queueId + "}:last-active";
    }

    /**
     * admit된 토큰의 만료 시각 ZSet (score = 만료 epoch ms, member = {@code "seq|identifier"}).
     *
     * <p>TTL 만료 → WAITING 복귀 배치가 {@code ZRANGEBYSCORE 0 now}로 claim하는 대상이다 (§80).
     */
    public static String admitted(String queueId) {
        return "queue:{" + queueId + "}:admitted";
    }

    /** 마지막 admit seq. {@code /status} 전광판 원본 (§79). admit.lua가 조건부로 올린다. */
    public static String admitWatermark(String queueId) {
        return "queue:{" + queueId + "}:admit-watermark";
    }

    /**
     * 폴링 간격 사다리 오버라이드 (§79). <b>평상시 대부분의 큐에는 이 키가 없다</b> —
     * 없으면 코드 상수({@code PacingTier.DEFAULT})가 쓰이므로 관리 대상이 0이다.
     *
     * <p>존재 이유는 장애 시 "전원 폴링 간격 2배"를 서버가 즉시 할 수 있다는 것 하나다.
     * 큐 생성 시 미리 채워두지 않는다 — 폴백 분기는 어차피 못 지우고(AFTER_COMMIT 실패·Redis
     * 유실), 미리 쓰면 쓰기 경로와 삭제 경로만 늘어난다 (§79 D4).
     *
     * <p>값 형식은 {@code "50:2,1000:5,5000:10,10000:15,*:20"} — {@code PacingTier.parse} 참조.
     * {@code admit-watermark}·{@code seq}와 같은 해시태그라 {@code MGET} 한 번에 실린다.
     */
    public static String pacing(String queueId) {
        return "queue:{" + queueId + "}:pacing";
    }

    /**
     * {@code admit-by-token} 접두사 (뒤에 tokenId가 붙는다). Polling 응답용 admitToken 조회.
     *
     * <p><b>접두사를 Java가 만드는 이유 (§80 ⑥):</b> admit.lua는 이 키를 {@code KEYS[]}에 선언할 수
     * 없다 — 두 번째 조각(tokenId)이 런타임 값이다. 선언이 없으면 Redis의 {@code CROSSSLOT} 사전
     * 검사가 <b>아예 걸리지 않고</b>, 선언 없는 접근은 {@code ERR Script attempted to access a
     * non local key}로 <b>이 노드가 그 키를 소유하는지</b>만 본다. 즉 <b>슬롯이 달라도 그 노드가
     * 우연히 소유하면 조용히 성공</b>한다(마스터 4대 = 약 25%). 남는 방어는
     * {@code QueueKeysSlotTest}의 리플렉션 전수 슬롯 단언 하나뿐이므로, 접두사가 {@code .lua}
     * 파일에 살면 그 단언이 닿지 못한다.
     */
    public static String admitByTokenPrefix(String queueId) {
        return "queue:{" + queueId + "}:admit-by-token:";
    }

    /**
     * 완성된 {@code admit-by-token} 키 (Polling·complete 경로에서 직접 조회할 때).
     *
     * <p>반드시 접두사 메서드를 재사용한다 — 따로 조립하면 같은 문자열이 두 군데 살아 갈라진다
     * (단일 출처 붕괴, §80 ⑥).
     */
    public static String admitByToken(String queueId, String tokenId) {
        return admitByTokenPrefix(queueId) + tokenId;
    }

    /**
     * {@code admit-by-admit} 접두사 (뒤에 admitToken이 붙는다). verify용 역참조.
     *
     * <p><b>값은 {@code "tokenId|seq|issuedAt|identifier"}</b>다. tokenId만 담으면 verify가 돌려줄 identifier를
     * DB에서만 얻을 수 있어, Kafka 적재가 아직 안 끝난 정상 토큰이 404가 된다. 읽는 쪽은
     * <b>첫 {@code '|'}로만</b> 쪼갠다(identifier는 Tenant 자유 문자열이라 {@code '|'}가 들어올 수 있다).
     */
    public static String admitByAdmitPrefix(String queueId) {
        return "queue:{" + queueId + "}:admit-by-admit:";
    }

    /** 완성된 {@code admit-by-admit} 키. 접두사 메서드 재사용 (위와 같은 이유). */
    public static String admitByAdmit(String queueId, String admitToken) {
        return admitByAdmitPrefix(queueId) + admitToken;
    }

    /**
     * admit 멱등 키. 결과 payload를 들고 있어 재시도에 REPLAY로 답한다 (TTL 300s).
     *
     * <p>{@code requestId}는 <b>Tenant가 정하는 값</b>이라 큐 스코프가 필수다 — 전역 키로 두면
     * 다른 테넌트가 같은 requestId를 보냈을 때 남의 결과를 받는다.
     */
    public static String admitIdem(String queueId, String requestId) {
        return "queue:{" + queueId + "}:admit-idem:" + requestId;
    }
}
