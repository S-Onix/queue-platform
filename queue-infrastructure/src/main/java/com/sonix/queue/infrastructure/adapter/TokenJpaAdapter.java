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
import org.springframework.transaction.annotation.Isolation;
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
     * 이유: 신규 적재(ENQUEUED) SQL. {@code saveAllIfAbsent} 의 파라미터 인덱스가 <b>이 컬럼 순서</b>에 붙어 있다.
     * 🔑 ODKU 가 완전 no-op 인 것이 핵심 — 뒤늦은 ENQUEUED 가 전이된 행을 되돌리지 않는다.
     * 🪤 SET 절에 {@code ?} 를 쓰면 다중행 재작성이 조용히 꺼진다.
     *
     * @author sonix
     */
    private static final String ENQUEUE_INSERT =
            "INSERT INTO tokens (queue_id, seq, status, tenant_id, user_id, issued_at, token_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE token_id = token_id";

    /**
     * 이유: 상태 전이 UPSERT 의 INSERT 부분(ENQUEUED 는 {@link #ENQUEUE_INSERT} 가 맡는다).
     * 🔴 SET 절에 {@code ?} 를 쓰지 마라 — 재작성이 조용히 꺼져 500건 배치가 500왕복이 된다.
     * 🔴 {@code AS new} 별칭이 필요하고 컬럼명은 {@code tokens.}·{@code new.} 로 전부 한정한다.
     * 🔑 {@code admitted_at} 의 {@code ?} 는 §90 의 null 여부 보존용이다(값은 MySQL 이 찍는다).
     *
     * @author sonix
     */
    private static final String TRANSITION_INSERT = """
            INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at, admit_token, admitted_at, expired_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, IF(? IS NULL, NULL, UTC_TIMESTAMP(3)), ?) AS new
            ON DUPLICATE KEY UPDATE
            """;

    /**
     * 이유: 이벤트별 가드(§80 / FRS §6.4 의 표 그대로).
     * 🔴 <b>{@code status} 갱신은 반드시 마지막 줄이다.</b> ODKU 의 SET 절은 좌→우로 평가되고
     *    <b>아래 줄이 위 줄의 결과를 본다</b> — 먼저 쓰면 다음 줄 가드가 거짓이 되어
     *    {@code admit_token} 이 영원히 NULL 로 남고 <b>complete 전체가 죽는다</b>. 순서를 바꾸지 마라.
     * 🪤 {@code ENQUEUED} 가 없는 것은 의도다 — no-op UPSERT 는 {@link #ENQUEUE_INSERT} 가 갖는다.
     */
   private static final Map<TokenEventType, String> TRANSITION_SQL = transitionSql();

    private static Map<TokenEventType, String> transitionSql() {
        Map<TokenEventType, String> sql = new EnumMap<>(TokenEventType.class);
        // 이유: admitted_at 은 **이벤트 값이 아니라 MySQL 의 UTC_TIMESTAMP(3)** 이 찍는다(§90).
        //       앱 시계로 쓰면 한 창을 두 시계로 잰다(실측 S=398 이면 **원장 손상**이다).
        // 🔑 **값을 정하는 곳은 VALUES 절 하나다** — 이 줄에 또 쓰면 **무동작**이다(결함 주입 실측).
        // 🔧 단 "쓰는 곳은 한 곳뿐"으로 읽지 마라(§91) — COMPLETED ODKU 가 두 번째로 쓰고 거긴 하중을 받는다.
        // ❌ issued_at 은 같이 옮기지 마라 — 멱등 키의 절반이라 재처리마다 새 행이 생긴다.
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
        // 🔴 출발이 0 뿐인 것은 의도다(§36) — IN (0,1) 로 넓히면 늦은 입장이 거절된다.
        // 🔴 expired_reason 에도 **같은 가드가 필요하다** — 무조건 쓰면 complete 된 토큰에 사유가 박힌다.
        // 🪤 값을 '?' 대신 new.expired_reason 으로 받는다 — ODKU SET 절의 '?' 는 재작성을 조용히 끈다.
        // 🔴 **"ADMIT_TTL 은 DB 에 남지 않는다"는 거짓이었다**(259건) — 랙 구간엔 0→4 가 적용돼
        //    admit_token·admitted_at 이 영구 NULL 이 된다.
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
     * 이유: 신규 적재(ENQUEUED). 충돌하면 no-op 이다.
     * 문제: 🔴 <b>JPA 가 아니라 JdbcTemplate 인 것은 실행 시점을 맞추기 위해서다</b> — {@code persist} 는
     *       플러시까지 미루고 raw JDBC 는 즉시 실행해 <b>호출 순서와 실행 순서가 갈렸다</b>(2026-09-14).
     * 원인: COMPLETED 가 먼저 실행되면 <b>completed_at·admitted_at 이 NULL</b> 로 굳는다(영구 404 + 과금 누락).
     * 🔑 <b>두 경로를 같은 계층으로 맞추면 함정 자체가 사라진다</b>(플러시 강제는 주석으로 지켜야 한다).
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
     * 이유: 상태 전이를 가드 UPSERT 로 적재한다.
     * 문제: Hibernate {@code @SQLInsert} 는 엔티티당 <b>한 문장</b>만 가질 수 있는데 가드는 이벤트마다 다르다.
     * 해결: JdbcTemplate 으로 간다 — 배치는 그대로다({@code rewriteBatchedStatements} 로 한 왕복 다중행).
     * 🪤 배치 안에 같은 {@code (token_id, issued_at)} 이 두 번 있어도 dedup 하지 않는다 —
     *    ODKU 가 순서대로 흡수하고 전이는 <b>같은 값을 두 번 적용해도 결과가 같다</b>.
     *
     * @author sonix
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

    /**
     * 이유: 가드 UPDATE 한 문장. {@code @Modifying} 은 트랜잭션이 없으면 실행되지 않아 여기서 연다.
     * 문제: 호출자의 트랜잭션에 얹혀 있던 때는 Redis 왕복과 Kafka 동기 발행(12초)까지 커넥션을 쥐었다.
     * 해결: 트랜잭션을 DB 작업 하나로 좁혔다. 🔴 지우면 complete 가 런타임에 죽는다(markCompleted_withoutAmbientTransaction).
     * 🔴 {@code READ COMMITTED} 여야 한다 — WHERE 가 유니크키 절반이라 RR 에선 갭락 ↔ 컨슈머 INSERT 데드락(1213)으로 500
     *    (실측 24/24, 커버 markCompleted_doesNotDeadlockWithConsumerInsert). §95 · §96-8
     *
     * @author sonix
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
