package com.sonix.queue.domain.queue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TokenRepository {
    /**
     * WAITING 토큰들을 벌크로 멱등 적재한다.
     *
     * <p><b>멱등 계약</b>: 같은 {@code (tokenId, issuedAt)}는 조용히 무시된다
     * (UNIQUE 위 no-op upsert). Kafka At-Least-Once 재전달이 일상이므로 호출자는
     * 중복 검사 없이 그대로 다시 넘기면 된다.
     *
     * @param tokens 적재할 토큰들 (Consumer가 poll한 배치 단위, 보통 수백 건)
     */
    void saveAllIfAbsent(List<Token> tokens);

    /**
     * 이유: {@code ENQUEUED} 외의 생명주기 이벤트를 <b>가드 UPSERT</b> 로 적재한다.
     * 문제: 도착 순서가 뒤집힌다 — Redis 커밋이 Kafka 발행보다 먼저라 ADMITTED 가 ENQUEUED 를 앞선다.
     * 해결: 행이 없으면 {@code targetStatus()} 로 INSERT, 있으면 <b>허용 출발 상태에서만</b> 전이한다.
     * 🔴 호출자는 <b>같은 타입이 연속하는 구간</b>으로 넘긴다 — ADMITTED·EXPIRED 가 {@code status = 0} 가드다.
     * 🔧 COMPLETED 는 §91 에서 {@code IN (0,1)} 로 넓어져 <b>순서에 의존하지 않는다</b>.
     *
     * @author sonix
     * @param type {@code ENQUEUED}는 허용하지 않는다 — 그건 {@link #saveAllIfAbsent}의 몫이다
     */
    void applyTransition(TokenEventType type, List<Token> tokens);

    /**
     * 신원 조회 — verify가 Redis 히트일 때 identifier를 얻는 경로 (FRS §6.5).
     *
     * <p>🔴 <b>status·admitted_at 술어를 걸지 마라.</b> DB의 status는 컨슈머가 ADMITTED를 소비한
     * 뒤에야 갱신되므로, status=1을 걸면 컨슈머 랙 구간의 <b>정상 토큰이 404</b>가 된다.
     * 자격 증명은 이미 Redis {@code admit-by-admit} 키의 생존이 하고 있다.
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
     * <p>{@code status IN (0, 1)}로 관대하게 잡는다 — 컨슈머가 ADMITTED 를 아직 적재하지 않아
     * status 가 0 인 정상 입장자가 complete 를 먼저 부를 수 있다(§91). 무한 소급은 {@code validWindowSeconds}가 막는다.
     *
     * @return 갱신된 행 수. 0이면 대상 없음(404).
     */
    int markCompleted(String queueId, long tenantId, String tokenId, String admitToken,
                      LocalDateTime completedAt, int validWindowSeconds);

    /**
     * 이미 완료된 토큰의 완료 시각. {@link #markCompleted}가 0행을 돌려준 뒤에만 쓴다.
     *
     * <p>verify가 완료를 확정하게 되면서 {@code verify → complete}를 둘 다 부르는 정상 Tenant가
     * 0행 경로에 도달한다({@code markCompleted}의 술어가 {@code status IN (0,1)}이다).
     * 이때 400을 주면 아무 잘못 없는 통합이 깨지므로, <b>처음 완료된 시각</b>을 돌려준다.
     *
     * @return 완료 행이 없거나 admitToken이 다르면 빈 {@link Optional} → 호출자가 400
     */
    Optional<LocalDateTime> findCompletedAt(String queueId, long tenantId, String tokenId, String admitToken);

    // ── reconciliation (Sprint 9) ──

    /**
     * 이유: complete 창이 지나도록 {@code ADMIT_ISSUED} 에 남은 토큰을 만료로 확정한다 — 안 하면 영원히 1 로 남는다.
     * 원인: EXPIRED 가드가 {@code IF(status = 0, ...)} 라 1 에서 no-op 이다(늦은 complete 를 살리는 §36 의 의도).
     * 해결: 이것만 <b>직접 UPDATE</b> 다. 큐 단위로 끊고, 기준 시각은 호출자가 아니라 DB 가 정한다(§90).
     *
     * @author sonix
     * @param validWindowSeconds complete 창의 길이(= {@link Token#COMPLETE_VALID_WINDOW_SECONDS}). 짧게 주면 늦은 통보가 404 다
     * @param limit              한 번에 고칠 최대 행 수. Gap Lock을 피하려면 작게 끊는다
     * @return 실제로 만료 처리된 행 수
     */
    int expireStaleAdmitted(String queueId, int validWindowSeconds, int limit);

    /**
     * 대사 기준선 — 이 시각 이전에 발급된 것 중 가장 큰 seq.
     *
     * <p>정착 시간(settle window)을 seq로 환산한다. 방금 들어온 사람은 Kafka를 타는 중이라
     * Redis에만 있는 것이 <b>정상</b>이고, 그 구간을 갭으로 세면 컨슈머 지연이 곧 오탐이 된다
     * (실측: -500이 찍혔다가 40초 만에 0으로 회복).
     *
     * @return 해당 토큰이 없으면 {@code 0}
     */
    long findSettledMaxSeq(String queueId, LocalDateTime issuedBefore);

    /**
     * 대사의 DB 쪽 값 — {@code seq <= maxSeq}인 {@code WAITING} 행 수.
     *
     * <p>{@code waiting} ZSet과 <b>정확히 같은 집합</b>이어야 한다. admit된 사람은 ZSet에서 빠지고
     * DB에서도 {@code status = 1}이 되므로 양쪽에서 함께 빠진다.
     */
    long countWaitingUpTo(String queueId, long maxSeq);
}
