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
     * 큐 전광판 조회 — {@code GET /status}의 원본(§79). 읽기 1왕복({@code MGET} 3키:
     * {@code admit-watermark} · {@code pacing} · {@code seq}).
     *
     * <p>큐 실재 판정에 {@code seq}를 쓴다(§79 D3). {@code seq}는 첫 enqueue가 {@code INCR}로
     * 만들므로 실재하는 큐엔 반드시 있다. 이 판정이 없으면 <b>인증 없는</b> {@code /status}에
     * 임의 queueId를 던지는 것만으로 DB 조회를 유발할 수 있다.
     * 대가로 enqueue 0건인 실존 큐는 404지만, 대기 페이지는 enqueue 이후에 서빙된다(§78).
     *
     * <p>⚠️ 구현은 <b>읽기 라우팅</b>을 써야 한다. 쓰기 라우팅은 소유자 미상일 때 DB 배정 기록을
     * 조회하는데, 인증이 없는 이 경로에선 그 조회가 곧 증폭 경로다.
     *
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
     * admitToken TTL이 지난 항목을 집어(claim) 큐에서 뺀다 (§36 · §80 ⑧).
     * <b>되돌리지 않는다</b> — 만료는 복귀가 아니라 종료다(§36).
     *
     * <p>🔑 <b>이 호출 자체가 claim이다.</b> {@code ZRANGEBYSCORE} + {@code ZREM}이 한 Lua라
     * 실행 주체가 N대여도 가져가는 것은 한 대뿐이고 나머지는 빈 목록을 받는다 —
     * ShedLock·leader election이 필요 없다(CLAUDE.md "{@code @Scheduled} 단독 금지"의 예외).
     *
     * <p>중복 게이트 해제({@code HDEL tokens})는 반환 시점에 끝나 있다. 호출자가 할 일은
     * {@code EXPIRED} 발행뿐이고, 실패해도 Redis를 되돌릴 수단은 없다.
     * {@code last-active}는 건드리지 않는다 — 만료자는 {@code waiting}에 없어 sweep 대상이 아니다.
     *
     * @param nowMillis 현재 epoch ms(UTC). <b>호출자가 넘긴다</b>(Lua {@code TIME}은 비결정적)
     * @param limit     한 번에 집어올 최대 건수. 남은 몫은 다음 주기가 가져간다
     */
    List<ReclaimedToken> claimExpiredAdmits(String queueId, long nowMillis, int limit);

    /**
     * {@code inactiveTtl}이 지나도록 폴링이 없는 대기자를 집어(claim) 큐에서 뺀다(§82).
     *
     * <p>🔑 <b>§82가 Cancel API를 폐기해 이탈 회수의 유일한 경로다.</b> 취소 버튼이든 탭 닫기든
     * 네트워크 단절이든 Platform이 관측하는 신호는 "폴링이 멈춘다" 하나뿐이다.
     * claim 근거는 {@link #claimExpiredAdmits}와 같다.
     *
     * <p>⚠️ <b>{@code waiting}에 없는 seq는 건너뛴다.</b> 그 사람은 admit되어 큐 밖이거나 이미
     * 정리됐다. admit 대기자를 지우면 중복 게이트가 풀려 재-enqueue가 새 자리를 받고
     * 원래 자리는 유령이 된다. 다만 {@code last-active}에서는 빼 다음 주기 한도를 안 먹는다.
     *
     * @param cutoffMillis 이 시각 <b>이전</b>에 마지막 폴링한 사람이 대상
     *                     (= {@code now - inactiveTtl * 1000}). 큐마다 달라 호출자가 계산한다
     */
    List<ReclaimedToken> claimInactive(String queueId, long cutoffMillis, int limit);

    /**
     * {@code waitingTtl}(절대 만료)을 넘긴 대기자를 집어(claim) 큐에서 뺀다.
     * 판정 기준이 마지막 폴링이 아니라 <b>발급 시각</b>이라 폴링이 리셋하지 못한다.
     *
     * <p>🔑 <b>§82 구멍 ③의 마지노선이다.</b> enqueue만 하고 첫 폴링 전에 떠난 사람은
     * {@code last-active}에 멤버가 없어 {@link #claimInactive}가 영영 못 본다.
     *
     * <p>{@code waiting} <b>앞부분만</b> 훑는다. seq가 {@code INCR}이라 시간과 단조증가해
     * 오래된 사람은 항상 앞에 모인다 — 전수 스캔도, 별도 timestamp ZSet도 필요 없다.
     *
     * <p>⚠️ <b>조기 종료 금지.</b> {@code enqueue_bulk.lua}가 issuedAt을 청크 단위로 받아
     * 밀리초 역전이 가능하다. 7200초 TTL에서 역전은 무해하지만 조기 종료를 넣으면
     * <b>영구 누락</b>이 된다. 상한까지 전부 검사한다.
     *
     * <p>⚠️ <b>고아({@code tokens} Hash 미스)는 건드리지 않는다.</b> issuedAt을 몰라 만료 판정이
     * 성립하지 않고, 조용히 치우면 {@link #countOrphanedWaiting}이 영원히 0이 되어 탐지 수단이
     * 죽는다. 고아가 앞을 막으면 진짜 만료 대상이 상한에 안 들어오는데,
     * <b>그 상황을 알리는 것이 그 메트릭의 존재 이유</b>다.
     *
     * @param cutoffMillis 이 시각 <b>이전</b>에 발급된 사람이 대상(= {@code now - waitingTtl * 1000})
     * @param limit        한 번에 <b>검사할</b> 최대 건수(회수 건수가 아니다)
     */
    List<ReclaimedToken> claimExpiredWaiting(String queueId, long cutoffMillis, int limit);

    /**
     * <b>관측 전용</b> — {@code waiting} 맨 앞에서 {@code tokens} Hash가 <b>없는</b> 멤버 수
     * (§80 U9). 아무것도 지우지 않는다.
     *
     * <p>고아의 정의는 <b>위치가 아니라 Hash 미스</b>다. {@code admit.lua}는 {@code ZPOPMIN}으로
     * 뽑은 사람의 {@code HGET}이 미스면 원래 seq로 되돌린다 — 그 사람은 매 주기 뽑혔다 돌아오며
     * {@code count} 슬롯을 먹고 admit은 그를 지나가지 못한다. 그래서 고아는 항상 앞에 쌓이고,
     * {@code ZRANGE 0 N}으로 상한을 두면 큐가 30만이어도 훑는 양이 고정된다.
     *
     * <p>🪤 <b>admit watermark 위치로 판정하면 안 된다 — 실측 기각(2026-08-24).</b>
     * dev Redis에서 <b>15,144건이 전부 오탐</b>이었다. 키를 선택적으로 지우는 경로
     * (테스트 정리 · §71 복구 · 부분 유실)가 watermark와 {@code waiting}의 정합을 깬다.
     *
     * <p>🪤 <b>"첫 폴링 전 이탈"(§82 구멍 ③)은 못 잡는다</b> — 그들은 Hash가 멀쩡해 고아가
     * 아니다. 그쪽은 {@code waitingTtl}이 받는다.
     *
     * <p>범위 조회와 {@code HMGET}을 원자로 묶지 않아도 된다. 그 사이 complete가 나면 한 주기만
     * 1건 더 세어지고 다음 주기에 사라진다 — 반대 방향(놓침)은 없다.
     *
     * @return 앞 구간에서 발견된 고아 수. 상한을 넘으면 그 값에서 포화한다
     */
    long countOrphanedWaiting(String queueId);

    /**
     * 대사의 Redis 쪽 값 — {@code waiting}에서 {@code score(seq) <= maxSeq}인 멤버 수(관측 전용).
     * {@link TokenRepository#countWaitingUpTo}와 짝이고 <b>차의 부호가 방향을 말한다</b>.
     *
     * <ul>
     *   <li><b>양수</b>(Redis가 많다) — 유령 토큰. {@code ENQUEUED} 발행 유실(§73 D15).
     *       100만건 실측에서 835건 발생한 그 갭이다</li>
     *   <li><b>음수</b>(DB가 많다) — 종료 이벤트 유실. 🔴 <b>자동 복구가 위험하다</b> —
     *       Redis 전손과 구분할 수단이 없어 전원을 만료로 오판할 수 있다</li>
     * </ul>
     *
     * <p>{@code ZCOUNT}라 O(log N)이다. 갭이 0이면 끝이고, 0이 아닐 때만 비싼 스캔으로 간다.
     */
    long countWaitingUpTo(String queueId, long maxSeq);

    /**
     * complete: 대기열·admit 흔적 제거(FRS §6.6 ②). 멱등이다.
     *
     * <p>{@code admit-by-admit}은 TTL 말고 삭제 경로가 여기뿐이라, 안 지우면 완료된 admitToken으로
     * 최대 60초간 verify가 통과한다. {@code tokens} Hash 필드도 여기서 지운다 — 그 필드의 존재가
     * enqueue의 중복 게이트(HSETNX)라 남겨두면 완료자가 다시 줄을 못 선다.
     *
     * <p>🔴 <b>{@code identifier}로 지우는 둘({@code waiting}·{@code tokens})은 tokenId가 일치할
     * 때만 지운다.</b> identifier는 회차 간 재사용되는 이름표이고, §36이 admitToken 만료 시 게이트를
     * 풀어 그 사람은 곧 다음 회차로 다시 선다. 그런데 complete 유효 창(300초)이 admitToken
     * TTL(60초)보다 길어 <b>240초 동안</b> 옛 회차의 늦은 complete가 도착할 수 있다 —
     * 대조 없이 지우면 다음 회차를 축출한다.
     * 반대로 {@code admitted} 멤버와 {@code admit-by-*}는 <b>무조건</b> 지운다. 키에 회차 고유 값이
     * 박혀 남의 것을 지울 수 없고, 여기에 가드를 걸면 위 60초 문제가 되살아난다.
     *
     * @param tokenId 회차 대조 기준. {@code tokens} Hash 값의 앞조각과 비교한다
     * @param seq     {@code admitted} ZSet 멤버가 {@code "seq|identifier"}라 필요하다
     */
    void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq);
}
