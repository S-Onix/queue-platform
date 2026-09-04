package com.sonix.queue.infrastructure.queue;

/**
 * Queue Engine Redis 키 중앙 관리.
 *
 * <p>캐시 키가 아니므로 {@code cache.RedisKeyFactory}가 아니라 여기서 관리한다.
 * ({@code ratelimit.RateLimitKeys}와 같은 이유 — Lua 원자 연산으로 다루는 원본 데이터)
 *
 * <p>🔴 <b>해시태그 {@code {queueId}} 필수.</b> 다중 키 Lua({@code enqueue_bulk} 등)는 키들이
 * 같은 슬롯이어야 하는데, 태그가 없으면 {@code CRC16(key) % 16384}가 키마다 갈려
 * {@code CROSSSLOT} 에러가 난다. 중괄호 안쪽만 슬롯 계산에 쓰이므로 queueId가 같으면
 * 같은 마스터가 수학적으로 보장된다.
 *
 * <p>🔴 <b>태그를 shard 단위로 옮기지 마라</b>(§75에서 기각된 안). {@code RedisQueueEngine.route}는
 * 소유자를 모를 때 {@code EXISTS queue:&#123;queueId&#125;:seq}를 양쪽 클러스터에 물어 판정하는데,
 * 키 이름에 shard가 들어가면 <b>질문을 만들려면 답을 이미 알아야</b> 한다. queueId는 테넌트에
 * 노출된 영구 식별자라 형식을 바꿀 수도 없다.
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
     * <p>🔴 <b>이 Hash의 필드 존재가 중복 게이트다</b>({@code enqueue_bulk.lua}의 {@code HSETNX}).
     * {@code waiting} ZSet은 게이트가 아니다 — admit되면 거기서 빠지므로 waiting으로 판정하면
     * admit된 사람의 재-enqueue가 새 tokenId·새 seq를 받는다(폴링 404 · 과금 중복 ·
     * {@code status=1} 고아 행).
     *
     * <p>🔴 <b>사람을 큐에서 빼는 경로는 반드시 이 필드를 마지막에 {@code HDEL}한다.</b> 현재 넷 —
     * {@code cleanup_completed}(complete) · {@code admit_expire}(§36) · {@code inactive_expire} ·
     * {@code waiting_expire}(§82). 안 지우면 영영 재입장 불가, 먼저 지우면 아직 큐에 있는 사람이
     * 폴링에서 404다.
     *
     * <p>🔴 <b>키는 identifier(사람)인데 값은 tokenId(회차)라, 지울 때 값을 봐야 한다.</b>
     * identifier는 회차 간 재사용되므로 그것만 보고 지우면 <b>다른 회차의 게이트를 지운다</b>
     * (= "늦은 complete가 재-enqueue한 사용자를 축출"한 결함). 네 경로는 tokenId를 {@code HGET}으로
     * 대조하거나({@code cleanup_completed}) identifier를 <b>지금 Redis에서</b> 얻어 이를 피한다.
     * <b>바깥에서 들고 온 identifier를 대조 없이 쓰는 경로를 새로 만들지 마라.</b>
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
     * 미리 채워두지 않는 것은 폴백 분기를 어차피 못 지우기 때문이다(§79 D4).
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
     * <p>🔴 <b>접두사를 {@code .lua} 파일로 옮기지 마라 (§80 ⑥).</b> tokenId가 런타임 값이라
     * admit.lua는 이 키를 {@code KEYS[]}에 선언할 수 없고, 선언이 없으면 {@code CROSSSLOT} 사전
     * 검사가 안 걸린다 — 남는 검사는 "이 노드가 그 키를 소유하는가"뿐이라 <b>슬롯이 달라도
     * 우연히 소유하면 조용히 성공</b>한다(마스터 4대 ≈ 25%). 유일한 방어가
     * {@code QueueKeysSlotTest}의 리플렉션 전수 단언인데, 접두사가 Java 밖에 있으면 닿지 못한다.
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
