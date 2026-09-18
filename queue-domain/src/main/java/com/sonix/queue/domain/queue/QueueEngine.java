package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueEngine {

    /**
     * 대기열 진입. 구현은 {@code RedisQueueEngine}.
     *
     * <p>{@code enqueue_bulk.lua} 단독이다 — 임계값 분기(하이브리드)는 §70이 폐기했다.
     * 단건 경로가 없으므로 저부하 요청도 배치 주기만큼 기다린다.
     */
    EnqueueResult enqueue(String queueId, String identifier);

    /**
     * 이유: 큐 전광판 조회 — {@code GET /status} 의 원본(§79). MGET 3키로 읽는다.
     * 문제: 이 경로는 <b>인증이 없다</b> — 임의 queueId 로 DB 조회를 유발할 수 있다.
     * 해결: 큐 실재 판정에 {@code seq} 를 쓴다(§79 D3) — 첫 enqueue 가 INCR 로 만드는 키다.
     *       대가로 enqueue 0건인 실존 큐는 404 지만, 대기 페이지는 enqueue 이후에 서빙된다(§78).
     * ⚠️ 구현은 <b>읽기 라우팅</b>을 써라 — 쓰기 라우팅은 소유자 미상일 때 DB 를 조회한다.
     *
     * @author sonix
     * @return 큐가 없으면 빈 {@link Optional} → 호출자가 404
     */
    Optional<QueueBoard> readStatus(String queueId);

    /**
     * 폴링 소유권 검증 + keepalive. 쓰기(master).
     *
     * <p>seq는 큐별 {@code INCR}이라 추측이 자명하다. seq 존재만으로 판정하면 남의 대기 항목을
     * 들여다보고 keepalive까지 걸 수 있으므로 <b>검증과 갱신을 분리하지 않는다</b>.
     *
     * @param keepalive ⚠️ <b>무시된다</b>(§82 F안). 폴링이 오면 언제나 {@code last-active}를
     *                  갱신한다. API 하위호환용 자리다
     */
    boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis);

    /**
     * 대기열 앞에서 count명을 꺼내 admitToken을 발급한다. 쓰기(master), 전 구간 원자(§80).
     *
     * <p>같은 {@code requestId}면 대기열을 건드리지 않고 저장된 결과를 돌려준다
     * ({@link AdmitResult#replay()}). Tenant 재시도가 두 번 뽑아가는 것을 막는 유일한 장치다.
     * count 상한은 API DTO 검증이 강제한다(FRS §6.4).
     *
     * @param nowMillis 현재 epoch ms(UTC). <b>호출자가 넘긴다</b> — Lua에서 시각을 만들면
     *                  스크립트가 비결정적이 된다
     */
    AdmitResult admit(String queueId, String requestId, int count, long nowMillis);

    /**
     * verify: admitToken → (tokenId, identifier). 없으면 빈 Optional → 호출자가 DB fallback.
     *
     * <p>identifier까지 담는다. Redis에 tokenId만 있으면 identifier를 DB에서만 얻을 수 있어
     * <b>Kafka 적재가 안 끝난 정상 토큰이 404</b>가 된다. admit 시점에 이미 손에 있으므로
     * 같은 키에 함께 적는다(새 키가 아니다) → verify의 DB 읽기 0회.
     *
     * @return 롤링 배포 중 남은 구 포맷이면 identifier가 {@code null} → 호출자는 기존 DB 경로
     */
    Optional<AdmitRef> findAdmitRefByAdmitToken(String queueId, String admitToken);

    /**
     * polling: tokenId → admitToken. 아직 admit 전이거나 TTL(60s)이 지났으면 빈 Optional.
     *
     * <p>admit되면 {@code waiting}에서 빠져 {@link #verifyWaiting}이 false가 된다. 이 조회가
     * 없으면 <b>정상 입장자가 404</b>를 받고, 404는 클라이언트에게 재시도가 아니라 종료 신호다.
     *
     * <p>{@code admitted} ZSet이 아니라 이 키를 본다. 유효 창은 admitToken의 PX 60초인데
     * {@code admitted}는 그보다 오래 남는다(배치가 지운다). 돌려줄 admitToken도 여기에만 있다.
     */
    Optional<String> findAdmitTokenByTokenId(String queueId, String tokenId);

    /**
     * 이유: admitToken TTL 이 지난 항목을 집어(claim) 큐에서 뺀다(§36 · §80 ⑧).
     * 해결: <b>되돌리지 않는다</b> — 만료는 복귀가 아니라 종료다(§36).
     * 🔑 <b>이 호출 자체가 claim 이다</b> — ZRANGEBYSCORE + ZREM 이 한 Lua 라 N대여도 한 대만
     *    가져간다. ShedLock 이 필요 없다(CLAUDE.md "{@code @Scheduled} 단독 금지"의 예외).
     * 🪤 중복 게이트 해제(HDEL)는 반환 시점에 끝나 있다 — 호출자는 EXPIRED 발행만 하면 된다.
     *
     * @author sonix
     * @param nowMillis 현재 epoch ms(UTC). <b>호출자가 넘긴다</b>(Lua {@code TIME} 은 비결정적)
     * @param limit     한 번에 집어올 최대 건수. 남은 몫은 다음 주기가 가져간다
     */
    List<ReclaimedToken> claimExpiredAdmits(String queueId, long nowMillis, int limit);

    /**
     * 이유: {@code inactiveTtl} 이 지나도록 폴링이 없는 대기자를 집어 큐에서 뺀다(§82).
     * 🔑 §82 가 Cancel API 를 폐기해 <b>이탈 회수의 유일한 경로</b>다 — 취소든 탭 닫기든
     *    네트워크 단절이든 Platform 이 보는 신호는 "폴링이 멈춘다" 하나뿐이다.
     * ⚠️ {@code waiting} 에 없는 seq 는 건너뛴다. 지우면 중복 게이트가 풀려 재-enqueue 가
     *    새 자리를 받고 <b>원래 자리가 유령이 된다</b>. 다만 {@code last-active} 에서는 뺀다.
     *
     * @author sonix
     * @param cutoffMillis 이 시각 <b>이전</b>에 마지막 폴링한 사람이 대상. 큐마다 달라 호출자가 계산한다
     */
    List<ReclaimedToken> claimInactive(String queueId, long cutoffMillis, int limit);

    /**
     * 이유: {@code waitingTtl}(절대 만료)을 넘긴 대기자를 집어 큐에서 뺀다.
     * 원인: 판정이 마지막 폴링이 아니라 <b>발급 시각</b>이라 폴링이 리셋하지 못한다.
     * 해결: seq 가 시간과 단조증가하므로 앞부분만 훑는다 — 전수 스캔도 별도 ZSet 도 없다.
     * ⚠️ <b>조기 종료 금지</b> — 청크 단위 발급이라 밀리초 역전이 가능하다. 상한까지 전부 검사한다.
     * ⚠️ <b>고아는 건드리지 마라</b> — 조용히 치우면 {@link #countOrphanedWaiting} 이 영원히 0 이 된다.
     *
     * @author sonix
     * @param cutoffMillis 이 시각 <b>이전</b>에 발급된 사람이 대상(= now - waitingTtl × 1000)
     * @param limit 한 번에 검사할 최대 건수(회수 건수가 아니다)
     */
    List<ReclaimedToken> claimExpiredWaiting(String queueId, long cutoffMillis, int limit);

    /**
     * 이유: 좀비(고아)를 <b>세기만</b> 한다 — {@code waiting} 맨 앞의 {@code tokens} Hash 미스(§80 U9).
     * 원인: 고아의 정의는 <b>위치가 아니라 Hash 미스</b>다. {@code admit.lua} 가 미스면 원래 seq 로
     *       되돌리므로 고아는 항상 앞에 쌓인다 — 상한을 두면 30만 큐여도 훑는 양이 고정된다.
     * 🪤 <b>watermark 위치로 판정하지 마라 — 실측 기각</b>(dev 에서 15,144건 전부 오탐, 2026-08-24).
     * 🪤 "첫 폴링 전 이탈"(§82 구멍 ③)은 못 잡는다 — 그들은 Hash 가 멀쩡하다. {@code waitingTtl} 담당.
     *
     * @author sonix
     * @return 앞 구간에서 발견된 고아 수. 상한을 넘으면 그 값에서 포화한다
     */
    long countOrphanedWaiting(String queueId);

    /**
     * 이유: 대사의 Redis 쪽 값 — {@code waiting} 에서 {@code score(seq) <= maxSeq} 인 멤버 수.
     * 해결: {@link TokenRepository#countWaitingUpTo} 와 짝이고 <b>차의 부호가 방향</b>이다.
     *       <b>양수</b>=유령 토큰(ENQUEUED 발행 유실, 100만건에서 835건) ·
     *       <b>음수</b>=종료 이벤트 유실. 🔴 둘 다 탐지만 한다.
     * 🪤 음수에 자동 복구를 붙이지 마라 — Redis 전손과 구분 못 해 전원을 만료로 오판한다.
     *
     * @author sonix
     */
    long countWaitingUpTo(String queueId, long maxSeq);

    /**
     * 이유: complete 의 정리 — 대기열·admit 흔적 제거(FRS §6.6 ②). 멱등이다.
     * 문제: {@code tokens} Hash 필드가 enqueue 의 중복 게이트(HSETNX)라 남기면 완료자가 다시 못 선다.
     * 🔴 {@code identifier} 로 지우는 둘({@code waiting}·{@code tokens})은 <b>tokenId 가 맞을 때만</b> 지운다 —
     *    complete 창 300초가 admitToken TTL 60초보다 길어 <b>240초 동안</b> 옛 회차의 늦은 complete 가 온다.
     *    대조 없이 지우면 다음 회차를 축출한다. 반대로 {@code admitted}·{@code admit-by-*} 는 무조건 지운다.
     *
     * @author sonix
     * @param tokenId 회차 대조 기준. {@code tokens} Hash 값의 앞조각과 비교한다
     * @param seq     {@code admitted} ZSet 멤버가 {@code "seq|identifier"} 라 필요하다
     */
    void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq);

    /**
     * 이유: verify 의 정리 — {@link #cleanupCompleted} 와 같되 <b>{@code admit-by-admit} 은 남긴다</b>(§92).
     * 문제: 회차를 뜻하는 넷을 안 지우면 완료자가 60초 동안 줄 없이 재입장하고 과금이 갈린다.
     * 해결: verify 가 완료를 확정하므로(PR #48) 그 넷은 여기서 지운다.
     * 🔑 {@code admit-by-admit} 만 남기는 것은 그 키가 <b>두 재시도의 유일한 근거</b>라서다 —
     *    verify 재시도(§22)와 complete 의 Redis 폴백. 지우면 둘 다 404 다. PX 60s 가 거둔다.
     *
     * @author sonix
     */
    void cleanupVerified(String queueId, String identifier, String tokenId, long seq);

    /**
     * 이유: 이 큐에 {@code identifier} 로 발급된 토큰이 이미 있는지. <b>PAUSED 재진입 판정 전용</b>.
     * 문제: PAUSED 는 입구만 잠그는데 기존 대기자의 새로고침(재-enqueue)까지 막아 자리를 잃었다.
     * 원인: 신규/기존을 가르는 것은 {@code enqueue_bulk.lua} 의 HSETNX 인데, 상태 가드가 그보다
     *       <b>앞</b>에 있어 물어보기도 전에 막고 있었다.
     * 🔑 ACTIVE 경로에서는 호출되지 않는다 — 핫패스에 왕복이 붙지 않는다.
     *
     * @author sonix
     */
    boolean hasToken(String queueId, String identifier);

    /**
     * 이유: 삭제된 큐의 Redis 상태를 정리한다. <b>DB 원장은 건드리지 않는다</b>.
     * 해결: 둘로 나뉜다 — <b>즉시 삭제</b>는 대기 줄({@code waiting}·{@code last-active})과 순번·전광판,
     *       <b>유예 후 만료</b>는 {@code tokens}·{@code admitted} 다.
     * 원인: 삭제 시점에 입장권을 들고 가는 중인 사람의 verify·complete 를 <b>끝까지 받아주기 위해서다</b>.
     * 🪤 개별 입장권 키({@code admit-by-*})는 이미 TTL 이 있어 손대지 않는다.
     *
     * @author sonix
     */
    void purgeDeleted(String queueId);
}
