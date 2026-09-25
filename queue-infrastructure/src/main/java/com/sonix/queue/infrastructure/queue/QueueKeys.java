package com.sonix.queue.infrastructure.queue;

/**
 * 이유: Queue Engine Redis 키 중앙 관리. 캐시가 아니라 <b>Lua 원자 연산으로 다루는 원본</b>이다.
 * 🔴 <b>해시태그 {@code {queueId}} 필수</b> — 없으면 다중 키 Lua 가 {@code CROSSSLOT} 이다.
 * 🔑 <b>거부 기준이 둘이다</b>(실측) — 선언한 키는 슬롯이 갈리면 즉시 거부되지만, <b>선언 안 한 키는 같은 노드면 조용히 성공</b>한다(4대 ≈ 25%). 초록은 증거가 아니다.
 * 🪤 로컬 Sentinel 로는 원리적으로 못 잡는다(슬롯 개념이 없다) — 그래서 분기를 지웠다(§75 D28).
 * ❌ 태그를 shard 단위로 옮기지 마라(§75 기각) — 판정이 <b>질문을 만들려면 답을 알아야</b> 하게 된다.
 *
 * @author sonix
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
     * 이유: identifier → {@code "tokenId|issuedAt"} 매핑 Hash(발급 원장 + EXISTS 재사용).
     * 🔴 <b>이 Hash 의 필드 존재가 중복 게이트다</b>({@code HSETNX}) — {@code waiting} ZSet 이 아니다(admit 되면 빠져 재-enqueue 가 신규로 판정된다).
     * 🔴 사람을 큐에서 빼는 <b>네 경로가 이 필드를 마지막에 {@code HDEL}</b> 한다(안 지우면 영구 락아웃).
     * 🔴 <b>키는 identifier(사람)인데 값은 tokenId(회차)</b>라 지울 때 값을 대조해야 한다 —
     *    안 하면 늦은 complete 가 <b>다음 회차를 축출</b>한다(실제 결함이었다).
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
     * <p>입장권 만료를 회수하는 배치({@code admit_expire.lua})가 {@code ZRANGEBYSCORE 0 now}로 집어 가는 대상이다.
     * 대기열로 되돌리지 않는다(§36).
     */
    public static String admitted(String queueId) {
        return "queue:{" + queueId + "}:admitted";
    }

    /** 마지막 admit seq. {@code /status} 전광판 원본 (§79). admit.lua가 조건부로 올린다. */
    public static String admitWatermark(String queueId) {
        return "queue:{" + queueId + "}:admit-watermark";
    }

    /**
     * 이유: 폴링 간격 사다리 오버라이드(§79). <b>평상시 대부분의 큐엔 이 키가 없다.</b>
     * 해결: 없으면 코드 상수({@code PacingTier.DEFAULT})가 쓰여 관리 대상이 0 이다.
     * 🔑 존재 이유는 장애 시 <b>"전원 폴링 간격 2배"를 서버가 즉시</b> 할 수 있어야 하기 때문이다.
     * 🪤 미리 채워두지 않는다 — 폴백 분기는 어차피 못 지운다.
     *    값 형식은 {@code "50:2,1000:5,*:20"}({@code PacingTier.parse}).
     *
     * @author sonix
     */
    public static String pacing(String queueId) {
        return "queue:{" + queueId + "}:pacing";
    }

    /**
     * 이유: {@code admit-by-token} 접두사(뒤에 tokenId 가 붙는다). 폴링 응답용 admitToken 조회.
     * 🔴 <b>접두사를 {@code .lua} 로 옮기지 마라</b>(§80 ⑥) — tokenId 가 런타임 값이라 {@code KEYS[]} 에
     *    선언할 수 없고, 선언이 없으면 <b>슬롯 검사가 안 걸린다</b>.
     * 원인: 남는 검사는 "이 노드가 소유하는가"뿐이라 <b>우연히 소유하면 조용히 성공</b>한다(4대 ≈ 25%).
     * 해결: {@code QueueKeysSlotTest} 의 리플렉션 전수 단언이 유일한 방어다 — 그래서 접두사를 Java 에 둔다.
     *
     * @author sonix
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
