package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenRepository;
import com.sonix.queue.infrastructure.entity.TokenEntity;
import com.sonix.queue.infrastructure.entity.TokenEntityId;
import com.sonix.queue.infrastructure.repository.TokenJpaRepository;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TokenJpaAdapter implements TokenRepository {

    /**
     * 상태 전이 UPSERT의 INSERT 부분. <b>ENQUEUED만은 이 경로를 쓰지 않는다</b> —
     * 그건 가드가 필요 없는 no-op UPSERT라 {@link #ENQUEUE_INSERT}가 따로 갖는다.
     * 🔧 2026-09-14까지는 {@code TokenEntity.@SQLInsert}가 그 일을 했는데, JPA 지연 플러시가
     * 같은 트랜잭션 안에서 실행 순서를 뒤집어 raw JDBC로 옮겼다({@code saveAllIfAbsent} 주석).
     *
     * <p><b>🔴 {@code AS new} 별칭을 쓰는 이유 (MySQL 8.0.19+):</b> 같은 뜻의 {@code VALUES(col)}은
     * 8.0.20부터 deprecated라 서버(8.0.46)가 <b>사용 1회마다 경고 1287</b>을 돌려준다(실측:
     * 한 문장에 2회 사용 → 경고 2건). ADMITTED는 결국 enqueue와 같은 건수가 흐르므로 그만큼이
     * 배치마다 쌓인다.
     *
     * <p><b>별칭을 쓰면 컬럼 이름이 양쪽에 존재하게 되어 ODKU 안의 맨 컬럼명이 모호해진다</b> —
     * {@code status = IF(status = 0, ...)}는 {@code ERROR 1052 Column 'status' is ambiguous}로
     * 실패한다(실측). 그래서 <b>기존 행은 {@code tokens.}, 새 값은 {@code new.}</b>로 전부 한정한다.
     *
     * <p><b>🔴 이 절에 {@code ?}를 쓰면 안 된다.</b> Connector/J는 VALUES 절이 끝난 뒤에 파라미터가
     * 있으면 다중행 재작성을 포기한다({@code QueryInfo}의 {@code valuesClauseEndFound} 분기).
     * 예외도 로그도 없이 500건 배치가 500왕복이 된다. 그래서 가드의 상수는 전부 리터럴이고,
     * 값이 필요한 자리는 {@code new.col}로 참조한다. {@code AS} 절은 Connector/J가
     * VALUES 절의 끝으로 인식하므로(같은 클래스의 {@code AS_CLAUSE} 분기) 재작성이 유지된다 —
     * {@code TokenUpsertRewriteTest}가 이 사실을 왕복 횟수로 못박는다.
     *
     * <p>🔴 <b>{@code admitted_at} 자리의 {@code IF(? IS NULL, NULL, UTC_TIMESTAMP(3))}</b>는
     * 충돌이 없어 <b>INSERT로 들어가는 경로</b>(= ENQUEUED가 유실돼 선행 행이 없는 경우)에서도
     * 값의 출처를 MySQL 시계로 맞추기 위한 것이다(§90). SET 절만 바꾸면 이 경로만 앱 시계로
     * 남아 <b>"거의 맞는데 가끔 틀리는"</b> 상태가 된다.
     * {@code ?}를 그대로 두는 것은 <b>null 여부를 보존</b>하기 위해서다 — 이 템플릿은 4종 이벤트가
     * 공유하므로 무조건 {@code UTC_TIMESTAMP(3)}로 바꾸면 {@code EXPIRED}·{@code COMPLETED}가
     * 신규 행을 만들 때도 {@code admitted_at}이 찍혀, 입장한 적 없는 토큰을
     * {@code SUM(admitted_at IS NOT NULL)}(= 입장권 개수의 유일한 근거)이 세어 버린다.
     * {@code ?}가 <b>VALUES 절 안</b>이라 다중행 재작성은 유지된다.
     */
    /**
     * 신규 적재(ENQUEUED) SQL. {@code TokenEntity.@SQLInsert}의 원문을 그대로 옮긴 것이다 —
     * <b>컬럼 순서까지 같아야 한다</b>({@code saveAllIfAbsent}의 파라미터 인덱스가 이 순서에 붙어 있다).
     *
     * <p>ODKU가 {@code token_id = token_id}(완전 no-op)인 것이 핵심이다. 뒤늦게 온 ENQUEUED가
     * 이미 전이된 행을 <b>되돌리지 않는다</b>는 보장이 여기서 나온다.
     * 🪤 SET 절에 {@code ?}를 쓰면 다중행 재작성이 조용히 꺼진다(TRANSITION_INSERT 주석 참조).
     */
    private static final String ENQUEUE_INSERT =
            "INSERT INTO tokens (queue_id, seq, status, tenant_id, user_id, issued_at, token_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE token_id = token_id";

    private static final String TRANSITION_INSERT = """
            INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at, admit_token, admitted_at, expired_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, IF(? IS NULL, NULL, UTC_TIMESTAMP(3)), ?) AS new
            ON DUPLICATE KEY UPDATE
            """;

    /**
     * 이벤트별 가드 (§80 / FRS §6.4의 표 그대로).
     *
     * <p><b>🔴 {@code status} 갱신은 반드시 마지막 줄이다.</b> MySQL ODKU의 SET 절은 좌 → 우로
     * 평가되고 <b>아래 줄은 위 줄이 바꾼 값을 본다</b>. {@code status}를 먼저 쓰면 다음 줄의
     * {@code IF(tokens.status = 0, ...)}이 <b>이미 1로 바뀐 값</b>을 보게 되어 거짓이 되고,
     * {@code admit_token}이 영원히 NULL로 남는다. 그러면 complete의 {@code admit_token = ?}
     * 술어가 절대 맞지 않아 complete 전체가 죽는다. 줄 순서를 바꾸지 말 것.
     *
     * <p>{@code ENQUEUED}가 없는 것은 의도다 — no-op UPSERT는 {@link #ENQUEUE_INSERT}가 갖는다.
     * 여기 넣으면 같은 규칙이 두 곳에 생긴다. (🔧 그 자리는 원래 {@code TokenEntity.@SQLInsert}였다)
     */
    private static final Map<TokenEventType, String> TRANSITION_SQL = transitionSql();

    private static Map<TokenEventType, String> transitionSql() {
        Map<TokenEventType, String> sql = new EnumMap<>(TokenEventType.class);
        // 🔴 admitted_at은 **이벤트가 실어온 값이 아니라 UTC_TIMESTAMP(3)** 이다 (§90).
        //    🔑 **ADMITTED 경로에서** 값을 정하는 곳은 **VALUES 절 하나다**(TRANSITION_INSERT).
        //       여기 `new.admitted_at`은 그 결과를 가리킬 뿐이라 ODKU와 INSERT가 같은 출처를 쓴다.
        //       이 줄에 UTC_TIMESTAMP(3)을 또 쓰면 **무동작**이다(결함 주입 실측: SET만 되돌려도 전부 초록).
        //    🔧 **"쓰는 곳은 한 곳뿐"이라고 읽지 마라 (§91에서 갈렸다).** COMPLETED ODKU가
        //       `admitted_at`을 **두 번째로** 쓴다 — 거긴 무동작이 아니라 하중을 받는다.
        //       COMPLETED가 ADMITTED보다 먼저 도착해 `status=0`에서 완료를 확정하면,
        //       뒤늦은 ADMITTED가 이 `status = 0` 가드에 걸려 no-op이 되어 **아무도 안 채우기** 때문이다.
        //       두 경로가 각자 자기 자리를 채우고, 서로 `IS NULL` 조건으로 겹치지 않는다.
        //    이 컬럼은 술어의 좌변이고, 우변은 셋 다 MySQL 시계다
        //    (TokenJpaRepository의 findAdmittedByAdmitToken · markCompleted · expireStaleAdmitted).
        //    좌변을 admit을 처리한 API 서버 시계로 쓰면 **한 창을 두 시계로 재게 된다** —
        //    그 서버가 S초 뒤처지면 DB 술어의 창이 max(300 − S, 0)으로 줄고, 실측(2026-09-09)에서
        //    S = 398이면 admit 0초 뒤 markCompleted가 0행이었다. 결과는 404가 아니라 원장 손상이다
        //    (QueueEngineService.complete 주석 참조).
        //    ⚠️ 대가는 이 값이 "admit 시각"이 아니라 **"컨슈머 적용 시각"** 이 되는 것이다.
        //       issued_at → admitted_at 대기 시간(queue_daily_stats.sum_wait_sec)에 Kafka lag이 섞인다.
        //       초 단위 집계라 정상 lag(≪1s)에서는 표현되지 않고, 정밀한 값은 앱이 직접 재는
        //       queue_admission_wait_seconds가 따로 갖고 있다.
        //    ❌ issued_at은 **같이 옮기지 않는다** — UNIQUE(token_id, issued_at) + 파티션 키 +
        //       Kafka 재처리 멱등의 절반이라, DB 시계로 만들면 재처리마다 새 행이 생긴다.
        //       식별자는 발생지 시계, 판정은 판정하는 곳의 시계다.
        sql.put(TokenEventType.ADMITTED, TRANSITION_INSERT + """
                admit_token = IF(tokens.status = 0, new.admit_token, tokens.admit_token),
                admitted_at = IF(tokens.status = 0, new.admitted_at, tokens.admitted_at),
                status      = IF(tokens.status = 0, 1, tokens.status)""");
        // COMPLETED 는 completed_at 을 여기서 찍는다 — verify 가 완료를 확정하는 경로(§92)가
        // complete API 를 안 거치므로, 안 채우면 그 행이 영원히 NULL 이다(findCompletedAt 404).
        // 🔴 **SET 절 네 줄이 전부 하중을 받고, `status` 는 반드시 마지막 줄이다** — ODKU 는 좌→우
        //    평가라 위로 올리면 나머지 셋이 전부 거짓이 되어 세 컬럼이 NULL 이 된다(결함 주입 실측).
        // 가드가 `IN (0,1)` 인 이유·네 줄의 개별 근거·`status=4` 를 넓히면 안 되는 이유는 §91.
        sql.put(TokenEventType.COMPLETED, TRANSITION_INSERT + """
                admit_token  = IF(tokens.status IN (0, 1) AND tokens.admit_token IS NULL, new.admit_token, tokens.admit_token),
                admitted_at  = IF(tokens.status IN (0, 1) AND tokens.admitted_at IS NULL, UTC_TIMESTAMP(3), tokens.admitted_at),
                completed_at = IF(tokens.status IN (0, 1), UTC_TIMESTAMP(3), tokens.completed_at),
                status       = IF(tokens.status IN (0, 1), 2, tokens.status)""");
        // 🔴 출발이 0뿐인 것은 의도다 (§36). admitToken TTL 만료자는 status = 1이라 여기서
        //    no-op이 되고, 그래야 complete의 status IN (0, 1) + 300초 유효 창이 살아남는다.
        //    IN (0, 1)로 넓히면 늦은 입장이 INVALID_ADMIT_TOKEN이 된다.
        // 🔴 expired_reason에도 **같은 가드가 필요하다.** 무조건 쓰면 status = 1로 살아 있다가
        //    나중에 complete되는 토큰에 "만료됨" 사유가 박힌다 — 그 행은 status = 2인데
        //    expired_reason이 채워져 있어 통계가 거짓말을 한다.
        // 🪤 값을 '?'가 아니라 new.expired_reason으로 받는 이유: ODKU의 SET 절에 '?'를 쓰면
        //    rewriteBatchedStatements가 조용히 꺼진다(admit_token이 같은 우회를 하는 그 이유).
        //    INSERT의 VALUES 자리에 있는 '?'는 그 문제가 없다.
        // 🔴 **admitToken TTL 만료(ADMIT_TTL)가 "DB에 남지 않는다"는 거짓이었다** (실측 259건, 2026-09-18).
        //    랙 구간에는 ADMITTED가 아직 적재되지 않아 DB status가 0이고, 그러면 이 가드가 참이 되어
        //    0→4를 적용한다. 뒤늦은 ADMITTED는 위 가드(status=0)에 걸려 no-op이 되므로
        //    admit_token·admitted_at이 영구 NULL이다 → total_admit_issued 과소 계상.
        //    랙이 없으면 status=1이라 통째로 no-op이고, 그 경로의 사유는 ReconcileJob이 ADMIT_STALE로 쓴다.
        sql.put(TokenEventType.EXPIRED, TRANSITION_INSERT + """
                expired_reason = IF(tokens.status = 0, new.expired_reason, tokens.expired_reason),
                status         = IF(tokens.status = 0, 4, tokens.status)""");
        return Map.copyOf(sql);
    }

    private final TokenJpaRepository tokenJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    public TokenJpaAdapter (TokenJpaRepository tokenJpaRepository, JdbcTemplate jdbcTemplate) {
        this.tokenJpaRepository = tokenJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }


    /**
     * 신규 적재(ENQUEUED). 충돌하면 no-op이다.
     *
     * <p><b>🔴 JPA가 아니라 JdbcTemplate인 이유 — 같은 트랜잭션 안에서 실행 시점을 맞추기 위해서다.</b>
     * 예전에는 {@code tokenJpaRepository.saveAll}이었는데, JPA {@code persist}는 <b>플러시까지
     * INSERT를 미루고</b> {@code applyTransition}의 raw JDBC는 <b>즉시 실행</b>한다. 두 경로가 한
     * 트랜잭션에 섞이면 <b>호출 순서와 실행 순서가 갈린다</b>.
     *
     * <p>그 결과를 실측했다(2026-09-14). 한 배치에 같은 토큰의 {@code ENQUEUED}·{@code COMPLETED}가
     * 있으면 COMPLETED가 먼저 실행돼 <b>선행 행이 없으니 ODKU가 아니라 INSERT 경로</b>를 탄다.
     * {@code TRANSITION_INSERT}의 VALUES에는 {@code completed_at}이 <b>없고</b> COMPLETED 이벤트는
     * {@code admittedAt = null}을 싣는다 → 그 행은 {@code status=2}인데
     * <b>{@code completed_at = NULL}, {@code admitted_at = NULL}</b>로 굳는다.
     * §91이 SET 절 네 줄로 채우려던 칸이 그 경로에서만 통째로 비는 것이다 —
     * complete 재시도가 영구 404가 되고, 입장권 개수({@code SUM(admitted_at IS NOT NULL)})가
     * 과소 계상돼 <b>과금이 누락</b>된다.
     *
     * <p>🔑 <b>두 경로를 같은 계층으로 맞추면 그 함정 자체가 사라진다.</b> 한쪽에 플러시를 강제하는
     * 방법도 있지만, 그건 "왜 여기 플러시가 있는가"를 주석으로 지켜야 한다 — 이 레포는 실제로
     * 그 주석이 전파되지 않아 같은 함정을 두 번 밟았다
     * ({@code TokenJpaAdapterIntegrationTest}의 {@code @Transactional} 경고 참조).
     *
     * <p>SQL은 {@code TokenEntity.@SQLInsert}에 있던 것을 <b>그대로</b> 옮겼다. 컬럼 7개와
     * {@code ON DUPLICATE KEY UPDATE token_id = token_id}(완전 no-op)가 같아야 동작이 보존된다.
     */
    @Override
    public void saveAllIfAbsent(List<Token> tokens) {
        if (tokens.isEmpty()) return;

        // 같은 (tokenId, issuedAt)이 한 배치에 두 번 오면 앞의 것만 남긴다 — 기존 동작 보존.
        Map<TokenEntityId, Token> deduped = new LinkedHashMap<>();
        for (Token token : tokens) {
            deduped.putIfAbsent(new TokenEntityId(token.getTokenId(), token.getIssuedAt()), token);
        }
        List<Token> rows = List.copyOf(deduped.values());

        jdbcTemplate.batchUpdate(ENQUEUE_INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Token token = rows.get(i);
                ps.setString(1, token.getQueueId());
                ps.setLong(2, token.getSeq());
                ps.setInt(3, token.getStatus().getStatusCode());
                ps.setLong(4, token.getTenantId());
                ps.setString(5, token.getUserId());
                ps.setObject(6, token.getIssuedAt());
                ps.setString(7, token.getTokenId());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /**
     * <p>JPA가 아니라 JdbcTemplate인 이유: Hibernate의 {@code @SQLInsert}는 엔티티당 <b>한 문장</b>만
     * 가질 수 있는데, 가드는 이벤트마다 SQL이 다르다. 배치 자체는 그대로다 —
     * {@code addBatch}/{@code executeBatch}에 {@code rewriteBatchedStatements}가 걸려
     * 한 왕복의 다중행 INSERT가 된다.
     *
     * <p>배치 안에 같은 {@code (token_id, issued_at)}이 두 번 있어도 dedup하지 않는다.
     * ODKU가 순서대로 흡수하고, 전이는 <b>같은 값을 두 번 적용해도 결과가 같기</b> 때문이다.
     */
    @Override
    public void applyTransition(TokenEventType type, List<Token> tokens) {
        if (tokens.isEmpty()) return;

        String sql = TRANSITION_SQL.get(type);
        if (sql == null) {
            throw new IllegalArgumentException("전이 UPSERT가 없는 이벤트 타입: " + type);
        }

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Token token = tokens.get(i);
                ps.setString(1, token.getTokenId());
                ps.setString(2, token.getQueueId());
                ps.setLong(3, token.getTenantId());
                ps.setString(4, token.getUserId());
                ps.setLong(5, token.getSeq());
                ps.setInt(6, token.getStatus().getStatusCode());
                ps.setObject(7, token.getIssuedAt());
                // ENQUEUED 외의 대부분은 이 두 칸이 null이다 (EnqueueEvent의 null 규약 표 참조).
                if (token.getAdmitToken() == null) ps.setNull(8, Types.VARCHAR);
                else ps.setString(8, token.getAdmitToken());
                if (token.getAdmittedAt() == null) ps.setNull(9, Types.TIMESTAMP);
                else ps.setObject(9, token.getAdmittedAt());
                // EXPIRED에서만 값이 있다. 나머지 이벤트에선 NULL이고, ODKU 가드가
                // tokens.expired_reason을 보존하므로 기존 값을 지우지 않는다
                if (token.getExpiredReason() == null) ps.setNull(10, Types.TINYINT);
                else ps.setInt(10, token.getExpiredReason());
            }

            @Override
            public int getBatchSize() {
                return tokens.size();
            }
        });
    }

    @Override
    public Optional<Token> findByTokenId(String queueId, long tenantId, String tokenId) {
        return tokenJpaRepository.findOneByTokenId(queueId, tenantId, tokenId)
                .map(TokenEntity::toDomain);
    }

    @Override
    public Optional<Token> findAdmittedByAdmitToken(String queueId, long tenantId,
                                                    String admitToken, int freshSeconds) {
        return tokenJpaRepository.findAdmittedByAdmitToken(queueId, tenantId, admitToken, freshSeconds)
                .map(TokenEntity::toDomain);
    }

    @Override
    public int markCompleted(String queueId, long tenantId, String tokenId, String admitToken,
                             LocalDateTime completedAt, int validWindowSeconds) {
        return tokenJpaRepository.markCompleted(
                queueId, tenantId, tokenId, admitToken, completedAt, validWindowSeconds);
    }

    @Override
    public Optional<LocalDateTime> findCompletedAt(String queueId, long tenantId,
                                                   String tokenId, String admitToken) {
        return tokenJpaRepository.findCompletedAt(queueId, tenantId, tokenId, admitToken);
    }

    @Override
    @Transactional
    public int expireStaleAdmitted(String queueId, int validWindowSeconds, int limit) {
        return tokenJpaRepository.expireStaleAdmitted(queueId, validWindowSeconds, limit);
    }

    @Override
    public long findSettledMaxSeq(String queueId, LocalDateTime issuedBefore) {
        Long max = tokenJpaRepository.findSettledMaxSeq(queueId, issuedBefore);
        return max == null ? 0L : max;
    }

    @Override
    public long countWaitingUpTo(String queueId, long maxSeq) {
        return tokenJpaRepository.countWaitingUpTo(queueId, maxSeq);
    }
}
