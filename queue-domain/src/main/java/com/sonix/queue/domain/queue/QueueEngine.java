package com.sonix.queue.domain.queue;

import java.util.List;
import java.util.Optional;

public interface QueueEngine {

    /**
     * 대기열 진입. 구현은 {@code RedisQueueEngine}이다.
     *
     * <p>🔴 <b>하이브리드는 폐기됐다(§70).</b> 구 주석은 "1초 1000건 미만이면 단건 lua, 이상이면
     * bulk lua"였는데, <b>{@code enqueue_bulk.lua} 단독</b>으로 확정됐다. 임계값 분기 자체가 없다.
     * 단건 경로가 사라져 저부하 요청도 배치 주기만큼 기다린다 — 그 재조정은 별건이다(§70 D8).
     *
     * <p>구 주석에 남아 있던 이슈("1000건 기준을 polling까지 잡아야 하나")도 §79가 닫았다 —
     * 평상시 폴링은 {@code /status}가 받고 이 경로와 무관하다. 아래는 그 원문이다.
     * <pre>
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
     * @param keepalive ⚠️ <b>무시된다</b>(§82 F안). 예전엔 이 값이 갱신을 결정했으나 지금은
     *                  <b>폴링이 오면 언제나</b> {@code last-active}를 갱신한다. 호출부가 계속
     *                  넘기지만 {@code poll_verify.lua}는 읽지 않는다 — API 하위호환용 자리다
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
     * admitToken TTL이 지난 항목을 <b>집어(claim)</b> 큐에서 뺀다 (~~원래 seq 그대로 WAITING 복귀~~는 §36이 폐기)
     * (FRS §10 {@code TokenReclaimJob} · §36 · §80 ⑧).
     *
     * <p><b>이 호출 자체가 claim이다.</b> {@code ZRANGEBYSCORE 0 now} + {@code ZREM}이 한 Lua라
     * Redis 단일 스레드가 둘을 쪼개지 않는다. 실행 주체(queue-batch)가 N대여도 멤버를 가져가는
     * 것은 한 대뿐이고 나머지는 <b>빈 목록</b>을 받는다 — 그래서 ShedLock·leader election이
     * 필요 없다({@code CLAUDE.md} "{@code @Scheduled} 단독 금지" 규칙의 명시적 예외).
     *
     * <p><b>되돌리지 않는다</b>(§36). 중복 게이트 해제({@code HDEL tokens})는 반환 시점에 이미
     * 끝나 있고, 호출자가 할 일은 {@code EXPIRED} 발행뿐이며 그 발행이 실패해도 Redis를 되돌릴
     * 수단은 없다(admit의 Kafka 발행과 같은 비대칭).
     *
     * <p><b>{@code last-active}는 건드리지 않는다</b>(§80 확정). §36으로 복귀가 사라져 리셋 논점
     * 자체가 없어졌고, 만료자는 {@code waiting}에 없어 {@code inactiveTtl} sweep의 대상도 아니다.
     *
     * @param nowMillis 현재 epoch ms(UTC). <b>호출자가 넘긴다</b> — Lua의 {@code TIME}은 비결정적이다
     * @param limit     한 번에 집어올 최대 건수. 남은 몫은 다음 주기가 가져간다
     * @return 되돌아간 항목들. 비어 있으면 만료분이 없었거나 다른 인스턴스가 먼저 집어간 것이다
     */
    List<ReclaimedToken> claimExpiredAdmits(String queueId, long nowMillis, int limit);

    /**
     * {@code inactiveTtl}이 지나도록 폴링이 없는 대기자를 <b>집어(claim)</b> 큐에서 뺀다
     * (DECISIONS §82 · FRS §6.7).
     *
     * <p>🔴 <b>§82가 Cancel API를 폐기하면서 이탈 회수의 유일한 경로가 됐다.</b> 유저가 취소
     * 버튼을 누르든 탭을 닫든 네트워크가 끊기든, Platform이 관측하는 신호는 <b>"폴링이 멈춘다"</b>
     * 하나뿐이고 그 신호가 {@code last-active} ZSet이다.
     *
     * <p><b>이 호출 자체가 claim이다</b> — {@code claimExpiredAdmits}와 같은 근거다.
     *
     * <p><b>{@code cutoffMillis}는 호출자가 계산한다.</b> {@code inactiveTtl}이 큐마다 다르므로
     * ({@code Queue.getInactiveTtl()}) Lua는 그 값을 알 수 없다.
     *
     * <p><b>{@code waiting}에 없는 seq는 건너뛴다</b>(§36 역산 미스 규약). 그 사람은 admit되어
     * 큐 밖이거나 이미 정리된 사람이고, 둘 다 이 잡이 건드리면 안 된다 — admit 대기자를 지우면
     * 중복 게이트가 풀려 재-enqueue가 새 자리를 받고 원래 자리는 유령이 된다.
     * 다만 {@code last-active}에서는 빼므로 그 멤버가 다음 주기의 한도를 먹지 않는다.
     *
     * @param cutoffMillis 이 시각보다 <b>이전</b>에 마지막으로 폴링한 사람이 대상이다
     *                     (= {@code now - inactiveTtl * 1000})
     * @param limit        한 번에 집어올 최대 건수. 남은 몫은 다음 주기가 가져간다
     * @return 회수한 항목들. 비어 있으면 대상이 없었거나 다른 인스턴스가 먼저 집어간 것이다
     */
    List<ReclaimedToken> claimInactive(String queueId, long cutoffMillis, int limit);

    /**
     * {@code waitingTtl}(절대 만료)을 넘긴 대기자를 <b>집어(claim)</b> 큐에서 뺀다 (FRS §10).
     *
     * <p><b>{@code inactiveTtl}과 다른 점은 폴링이 리셋하지 않는다는 것</b>이다. 그래서 판정
     * 기준이 마지막 폴링 시각이 아니라 <b>발급 시각</b>({@code tokens} Hash의 issuedAt)이다.
     *
     * <p>🔑 <b>이 경로가 §82 구멍 ③의 마지노선이다.</b> enqueue만 하고 첫 폴링 전에 떠난 사람은
     * {@code last-active}에 멤버가 없어 {@link #claimInactive}가 영영 못 본다. 그 사람을 큐에서
     * 빼는 수단은 이것뿐이다.
     *
     * <p><b>{@code waiting} 앞부분만 훑는다.</b> seq는 {@code INCR} 발급이라 시간과 단조증가하므로
     * 오래된 사람은 항상 앞에 모여 있다. 전수 스캔({@code HSCAN tokens})도, 별도 timestamp ZSet도
     * 필요 없다 — 후자는 enqueue 핫패스에 쓰기를 하나 더 얹는 대가가 있고 그건 §82 A안과 같은 값이다.
     *
     * <p>⚠️ <b>조기 종료를 하지 않는다.</b> {@code enqueue_bulk.lua}가 issuedAt을 <b>청크 단위</b>로
     * 받아 청크 실행 순서에 따라 밀리초 역전이 가능하다. 7200초 TTL에서 역전 자체는 무해하지만
     * 조기 종료를 넣으면 <b>영구 누락</b>으로 바뀐다. 상한까지 전부 검사한다.
     *
     * <p>🔴 <b>고아({@code tokens} Hash 미스)는 건드리지 않는다.</b> issuedAt을 모르므로 만료 판정이
     * 성립하지 않고, 조용히 치우면 {@link #countOrphanedWaiting}이 영원히 0이 되어 탐지 수단이
     * 무력화된다. 대가는 고아가 앞자리를 점유하면 뒤의 진짜 만료 대상이 상한 안에 안 들어온다는
     * 것인데, <b>그 상황을 알려주는 것이 그 메트릭의 존재 이유</b>다.
     *
     * @param cutoffMillis 이 시각보다 <b>이전</b>에 발급된 사람이 대상이다
     *                     (= {@code now - waitingTtl * 1000}). 큐 설정이라 호출자가 계산한다
     * @param limit        한 번에 <b>검사할</b> 최대 건수(회수 건수가 아니다). 남은 몫은 다음 주기가 가져간다
     * @return 회수한 항목들. 비어 있으면 대상이 없었거나 다른 인스턴스가 먼저 집어간 것이다
     */
    List<ReclaimedToken> claimExpiredWaiting(String queueId, long cutoffMillis, int limit);

    /**
     * <b>관측 전용</b> — {@code waiting} 맨 앞에서 {@code tokens} Hash 항목이 <b>없는</b> 멤버 수
     * (§80 U9 좀비 탐지). 아무것도 지우지 않는다.
     *
     * <p><b>고아의 정의는 위치가 아니라 Hash 미스다.</b> {@code admit.lua}는 {@code ZPOPMIN}으로
     * 뽑은 사람의 {@code HGET tokens}가 미스면 <b>원래 seq로 되돌려 놓는다</b>. 그 사람은 매 admit
     * 주기마다 뽑혔다 되돌아가며 {@code count} 슬롯을 하나씩 먹고, admit은 그를 지나가지 못한다.
     * 판정은 그 조건을 <b>그대로</b> 본다 — {@code waiting}에 있는데 {@code tokens}에 없다.
     *
     * <p><b>맨 앞만 보면 되는 이유</b>: admit이 고아를 지나가지 못하므로 고아는 <b>항상 앞에 쌓인다</b>.
     * {@code ZRANGE 0 N}으로 상한을 두면 큐가 30만이어도 훑는 양이 고정된다.
     *
     * <p><b>🪤 admit watermark로 판정하지 않는다 — 실측으로 기각됐다(2026-08-24).</b>
     * 처음엔 "watermark보다 앞 순번인데 waiting에 있다"로 판정했는데, 실제 dev Redis에서
     * <b>15,144건이 전부 오탐</b>이었다. {@code admit-watermark}가 {@code waiting}과 갈라져 있었기
     * 때문이다(부하 테스트가 {@code waiting}을 재시드하며 watermark는 안 지웠다 —
     * seq=20504 / watermark=101 / waiting score 범위 [1..20504]).
     * 키를 선택적으로 지우는 경로(테스트 정리 · §71 복구 · 부분 유실)가 그 불변식을 깬다.
     * <b>위치는 고아의 증거가 아니다.</b>
     *
     * <p>구현이 두 명령(범위 조회 + {@code HMGET})을 원자로 묶지 않아도 된다. 그 사이 누가
     * {@code complete}하면 한 주기 동안만 1건이 더 세어지고 다음 주기에 사라진다 — gauge에 허용되는
     * 오차다. 반대 방향(고아를 놓침)은 없다.
     *
     * <p>🪤 <b>"첫 폴링 전 이탈"(§82 구멍 ③)은 잡지 못한다.</b> 그 사람들은 {@code tokens} Hash가
     * 멀쩡해 고아가 아니다 — 자기 차례가 오면 정상적으로 뽑힌다. 그쪽은 {@code waitingTtl}이 받는다.
     *
     * @return 맨 앞 구간에서 발견된 고아 수. 상한을 넘으면 그 값에서 포화한다(구현 상수 참조)
     */
    long countOrphanedWaiting(String queueId);

    /**
     * 대사의 Redis 쪽 값 — {@code waiting}에서 {@code score(seq) <= maxSeq}인 멤버 수 (관측 전용).
     *
     * <p>{@link TokenRepository#countWaitingUpTo}와 짝이다. 두 값의 <b>부호가 방향을 말해 준다</b>.
     * <ul>
     *   <li><b>양수</b>(Redis가 많다) — 유령 토큰. {@code ENQUEUED} 발행이 유실됐다(§73 D15).
     *       100만건 실측에서 835건 발생한 그 갭이다</li>
     *   <li><b>음수</b>(DB가 많다) — 종료 이벤트가 유실됐다. 🔴 <b>자동 복구가 위험하다</b> —
     *       Redis 전손과 구분할 수단이 없어 전원을 만료로 오판할 수 있다</li>
     * </ul>
     *
     * <p>{@code ZCOUNT}라 O(log N)이다. 갭이 0이면 여기서 끝나고, 0이 아닐 때만 비싼 스캔으로 넘어간다.
     */
    long countWaitingUpTo(String queueId, long maxSeq);

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
     * <p>🔴 <b>{@code identifier}로 지우는 둘({@code waiting}·{@code tokens})은 {@code tokenId}가
     * 일치할 때만 지운다.</b> identifier는 회차 간에 재사용되는 사람 이름표이고, §36이
     * admitToken TTL 만료 시 게이트를 풀어주므로 그 사람은 곧 <b>다음 회차</b>로 다시 줄에 선다.
     * 그런데 complete의 유효 창(300초)이 admitToken TTL(60초)보다 길어 <b>240초 동안</b> 옛 회차의
     * 늦은 complete가 도착할 수 있다. 대조 없이 지우면 그 다음 회차를 축출한다.
     * {@code admitted} 멤버와 {@code admit-by-*} 두 키는 반대로 <b>무조건</b> 지운다 — 키에 회차
     * 고유 값이 박혀 있어 남의 것을 지울 수 없고, 여기에 가드를 걸면 위 60초 문제가 되살아난다.
     *
     * @param tokenId 회차 대조 기준. {@code tokens} Hash 값의 앞조각과 비교한다
     * @param seq {@code admitted} ZSet 멤버가 {@code "seq|identifier"}라 필요하다
     */
    void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq);
}
