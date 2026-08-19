package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueEngine {

    /**
     * 대기열 진입 관련 인터페이스
     * >> 추후 RedisQueueEngine을 통해 실제 구현 코드 작성
     *
     * 하이브리드로 진행 예정
     * 1초에 1000건 이하의 요청인 경우 >> lua script
     * 1초에 1000건 이상의 요청이 올 경우 >> bulk lua script 진행
     *
     * 이슈사항 : 1초에 1000건에 대한 기준을 polling까지 잡아야하는가? >> Polling은 push 방식으로 변경 진행하여 실제 admit이 발생할 때에만 ranking 계산한다.
     * */
    EnqueueResult enqueue(String queueId, String identifier);

    /**
     * 큐 전광판 조회 — {@code GET /status}의 원본 (§79). <b>읽기 1왕복</b>({@code MGET} 3키).
     *
     * <p>키 셋이 같은 {@code &#123;queueId&#125;} 해시태그라 한 슬롯이고, 그래서 왕복이 하나다:
     * {@code admit-watermark}(전광판 값) · {@code pacing}(오버라이드, 없을 수 있다) ·
     * {@code seq}(큐 실재 판정).
     *
     * <p><b>미지 큐 판정에 {@code seq}를 쓰는 이유 (§79 D3):</b> {@code seq}는 그 큐의 첫 enqueue가
     * {@code INCR}로 만든다. 즉 큐가 실재하고 한 명이라도 들어왔다면 반드시 있다. 이 판정이 없으면
     * 인증 없는 {@code /status}에 임의 queueId를 던지는 것만으로 DB 조회를 유발할 수 있다 —
     * 여기서 끝내면 <b>Redis 1왕복 안에서 404</b>이고 MySQL로 내려가지 않는다.
     *
     * <p><b>대가</b>: enqueue가 0건인 실존 큐는 404다. 대기 페이지는 enqueue 이후에 서빙되므로(§78)
     * 실사용 경로가 아니다.
     *
     * <p>⚠️ 구현은 반드시 <b>읽기 라우팅</b>을 써야 한다. 쓰기 라우팅은 소유자를 못 찾을 때 DB
     * 배정 기록을 조회하는데, 이 경로는 인증이 없어 그 조회가 곧 증폭 경로가 된다.
     *
     * @return 큐가 실재하지 않으면 빈 {@link Optional} → 호출자가 404
     */
    Optional<QueueBoard> readStatus(String queueId);

    /**
     * 폴링 소유권 검증 + keepalive를 한 번에 수행. 쓰기(master).
     *
     * <p>seq에 해당하는 대기 항목이 있고, 그 항목에 발급된 tokenId가 인자와 일치할 때만 true.
     * seq는 큐별 INCR이라 추측이 자명하므로 seq 존재만으로 판정하면 남의 대기 항목을
     * 들여다보고 keepalive까지 걸 수 있다 — 검증과 갱신을 분리하지 말 것.
     *
     * @param keepalive true면 검증 통과 시 last-active를 nowMillis로 갱신
     * @return 검증 통과 여부
     */
    boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis);

    /**
     * 대기열 앞에서 count명을 꺼내 admitToken을 발급한다. 쓰기(master), 전 구간 원자(§80).
     *
     * <p>같은 {@code requestId}로 다시 부르면 대기열을 건드리지 않고 저장된 결과를 그대로 돌려준다
     * ({@link AdmitResult#replay()}). Tenant의 재시도가 두 번 뽑아가는 것을 막는 유일한 장치다.
     *
     * <p>count 상한은 여기서 막지 않는다 — API DTO의 검증이 강제한다(FRS §6.4).
     *
     * @param requestId Tenant가 정하는 멱등 키. 큐 스코프로 저장된다.
     * @param nowMillis 현재 epoch ms(UTC). admitToken 만료 시각의 기준이며 <b>호출자가 넘긴다</b> —
     *                  Lua에서 시각을 만들면 스크립트가 비결정적이 된다.
     */
    AdmitResult admit(String queueId, String requestId, int count, long nowMillis);

    /**
     * verify: admitToken → (tokenId, identifier) ({@code admit-by-admit} 조회).
     * 없으면 빈 Optional → 호출자가 DB fallback.
     *
     * <p><b>identifier까지 담는 이유:</b> verify가 Tenant에게 돌려줄 값은 identifier인데,
     * Redis에 tokenId만 있으면 identifier를 DB에서만 얻을 수 있다. Kafka 적재가 아직 안 끝난
     * 정상 토큰이 <b>404</b>가 되는 구간이 그래서 생겼다. admit 시점에 identifier가 이미 손에
     * 있으므로 같은 키에 함께 적는다(새 키가 아니다) — verify의 DB 읽기가 0회가 된다.
     *
     * @return {@link AdmitRef}. 롤링 배포 중 남은 구 포맷 값이면 identifier가 {@code null}이고,
     *         호출자는 tokenId로 기존 DB 경로를 탄다
     */
    Optional<AdmitRef> findAdmitRefByAdmitToken(String queueId, String admitToken);

    /**
     * polling: tokenId → admitToken ({@code admit-by-token} 조회). 아직 admit 안 됐거나
     * TTL(60s)이 지났으면 빈 Optional.
     *
     * <p><b>폴링이 이걸 봐야 하는 이유:</b> admit되면 {@code waiting} ZSet에서 빠지므로
     * {@link #verifyWaiting}이 false가 된다. 이 조회가 없으면 <b>정상 입장자가 404</b>를 받고,
     * 404는 클라이언트에게 재시도가 아니라 종료 신호다.
     *
     * <p><b>{@code admitted} ZSet이 아니라 이 키를 보는 이유:</b> 유효 창은 admitToken의 PX 60초이고
     * {@code admitted}는 그보다 오래 남는다(배치가 지운다). 즉 여기 값이 있다는 것 자체가
     * "지금 입장 가능"의 증명이며, 돌려줄 admitToken도 여기에만 있다.
     */
    Optional<String> findAdmitTokenByTokenId(String queueId, String tokenId);

    /**
     * admitToken TTL이 지난 항목을 <b>집어(claim)</b> 원래 seq 그대로 WAITING으로 되돌린다
     * (FRS §10 {@code AdmitTokenExpiryJob} · §36 · §80 ⑧).
     *
     * <p><b>이 호출 자체가 claim이다.</b> {@code ZRANGEBYSCORE 0 now} + {@code ZREM}이 한 Lua라
     * Redis 단일 스레드가 둘을 쪼개지 않는다. 실행 주체(queue-batch)가 N대여도 멤버를 가져가는
     * 것은 한 대뿐이고 나머지는 <b>빈 목록</b>을 받는다 — 그래서 ShedLock·leader election이
     * 필요 없다({@code CLAUDE.md} "{@code @Scheduled} 단독 금지" 규칙의 명시적 예외).
     *
     * <p>되돌리는 일은 반환 시점에 이미 끝나 있다. 호출자가 할 일은 {@code RETURNED} 발행뿐이며,
     * 그 발행이 실패해도 Redis를 되돌릴 수단은 없다(admit의 Kafka 발행과 같은 비대칭).
     *
     * <p><b>{@code last-active}는 건드리지 않는다</b>(§80 확정). 리셋하면 브라우저를 닫은 사람이
     * 복귀할 때마다 되살아나 영원히 회수되지 않는다.
     *
     * @param nowMillis 현재 epoch ms(UTC). <b>호출자가 넘긴다</b> — Lua의 {@code TIME}은 비결정적이다
     * @param limit     한 번에 집어올 최대 건수. 남은 몫은 다음 주기가 가져간다
     * @return 되돌아간 항목들. 비어 있으면 만료분이 없었거나 다른 인스턴스가 먼저 집어간 것이다
     */
    List<ExpiredAdmit> claimExpiredAdmits(String queueId, long nowMillis, int limit);

    /**
     * complete: 대기열·admit 흔적 제거 (FRS §6.6 ②). 멱등 — 없는 키를 지워도 무해하다.
     *
     * <p>{@code admit-by-admit}은 TTL 말고 삭제 경로가 여기뿐이다. 지우지 않으면 완료된
     * admitToken으로 최대 60초간 verify가 계속 통과한다.
     *
     * <p><b>{@code tokens} Hash 필드도 여기서 지운다.</b> 그 필드의 존재가 enqueue의 중복
     * 게이트(HSETNX)라, 남겨두면 완료한 사람이 다시 대기열에 들어오지 못한다. 반대로 지우는
     * 순서를 앞당기면 아직 큐에 있는 사람이 폴링에서 영영 404가 된다(구현 주석 참조).
     *
     * @param seq {@code admitted} ZSet 멤버가 {@code "seq|identifier"}라 필요하다
     */
    void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq);
}
