package com.sonix.queue.domain.queue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TokenRepository {
    /**
     * WAITING 토큰들을 벌크로 멱등 적재한다.
     *
     * <p><b>멱등 계약</b>: 이미 존재하는 토큰(동일 {@code (tokenId, issuedAt)})은
     * 조용히 무시되고 오류를 던지지 않는다. outbox at-least-once로 같은 이벤트가
     * 재처리되거나 발행 재시도로 중복돼도 중복 row가 생기지 않는다.
     *
     * <p>구현은 UNIQUE(token_id, issued_at) 위에서 upsert(no-op)로 이 계약을 만족한다.
     * 따라서 호출자는 중복 여부를 사전 검사하지 않아도 되고, 재소비 시 그대로 다시 넘기면 된다.
     *
     * @param tokens 적재할 토큰들 (Consumer가 poll한 배치 단위, 보통 수백 건)
     */
    void saveAllIfAbsent(List<Token> tokens);

    /**
     * {@code ENQUEUED} 외의 생명주기 이벤트를 <b>가드 UPSERT</b>로 적재한다 (§80 / FRS §6.4).
     *
     * <p>행이 없으면 {@code type.targetStatus()}로 INSERT하고, 있으면 <b>허용 출발 상태일 때만</b>
     * 전이한다. 두 성질이 함께 필요하다:
     * <ul>
     *   <li><b>INSERT</b> — 프로듀서가 여러 WAS라 도착 순서가 뒤집힌다. 특히 enqueue Lua의
     *       {@code ZADD}가 Kafka 발행보다 먼저라 {@code ENQUEUED}보다 {@code ADMITTED}가 먼저
     *       올 수 있다. 그때 행을 만들어 두면 뒤늦은 {@code ENQUEUED}의 no-op UPSERT가 흡수한다.</li>
     *   <li><b>가드</b> — Kafka는 At-Least-Once라 재전달이 일상이다. {@code COMPLETED}(2)인 행에
     *       {@code ADMITTED}가 다시 와도 2가 유지돼야 한다. 멱등이 본질이지 예외 처리가 아니다.</li>
     * </ul>
     *
     * <p><b>호출자는 같은 타입이 연속하는 구간 단위로 넘긴다.</b> 타입별로 모아서 넘기면
     * 같은 토큰의 {@code ADMITTED}→{@code COMPLETED} 순서가 뒤집혀
     * {@code COMPLETED}가 먼저 no-op이 되고 그 토큰은 영원히 완료되지 않는다.
     *
     * @param type {@code ENQUEUED}는 허용하지 않는다 — 그건 {@link #saveAllIfAbsent}의 몫이다
     */
    void applyTransition(TokenEventType type, List<Token> tokens);

    /**
     * 신원 조회 — verify가 Redis 히트일 때 identifier를 얻는 경로 (FRS §6.5).
     *
     * <p><b>status·admitted_at 술어를 걸지 않는다.</b> Redis {@code admit-by-admit} 키가 살아 있다는
     * 사실이 이미 "60초 안에 admit됐다"를 증명하고, DB의 {@code status}/{@code admit_token}은
     * ADMITTED 이벤트를 컨슈머가 소비한 뒤에야 갱신되기 때문이다(§6.4 — 200이 보장하지 않는 것).
     * 여기에 status=1을 걸면 컨슈머 랙 구간의 <b>정상 토큰이 404</b>가 된다.
     *
     * <p>{@code queue_id}·{@code tenant_id}는 소유권 술어라 생략 불가.
     */
    Optional<Token> findByTokenId(String queueId, long tenantId, String tokenId);

    /**
     * verify의 DB fallback — Redis가 키를 잃었을 때만 탄다 (FRS §6.5).
     *
     * <p>기준 컬럼은 {@code issued_at}이 아니라 <b>{@code admitted_at}</b>이다. issued_at은
     * "줄을 선 시각"이라 두 시간 전일 수 있어 TTL 60초 판정에 쓸 값이 아니다(§80).
     *
     * @param freshSeconds admitToken TTL(60). {@code admitted_at > UTC_TIMESTAMP(3) - INTERVAL n SECOND}
     */
    Optional<Token> findAdmittedByAdmitToken(String queueId, long tenantId, String admitToken, int freshSeconds);

    /**
     * complete의 원자 상태 전이 (FRS §6.6). <b>탐색 키는 {@code token_id}</b>이고
     * {@code admit_token}은 입장 자격을 증명하는 술어다.
     *
     * <p>{@code status IN (0, 1)}로 관대하게 잡는다 — admitToken TTL이 만료돼 WAITING으로
     * 복귀했는데 Tenant는 이미 유저를 입장시킨 경우가 실재하고, 그때 거절하면 그 자리가
     * 영원히 안 빠진다. 무한 소급은 {@code validWindowSeconds}가 막는다.
     *
     * @return 갱신된 행 수. 0이면 대상 없음(404).
     */
    int markCompleted(String queueId, long tenantId, String tokenId, String admitToken,
                      LocalDateTime completedAt, int validWindowSeconds);
}
