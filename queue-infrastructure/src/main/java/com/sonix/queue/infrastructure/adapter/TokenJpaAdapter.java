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
     * 그건 {@code TokenEntity.@SQLInsert}가 이미 같은 모양으로 처리하고 있고, 초당 수만 건이
     * 흐르는 경로라 손대는 이득이 없다.
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
     */
    private static final String TRANSITION_INSERT = """
            INSERT INTO tokens (token_id, queue_id, tenant_id, user_id, seq, status, issued_at, admit_token, admitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS new
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
     * <p>{@code ENQUEUED}가 없는 것은 의도다 — no-op UPSERT는 {@code TokenEntity.@SQLInsert}에
     * 이미 있다. 여기 넣으면 같은 규칙이 두 곳에 생긴다.
     */
    private static final Map<TokenEventType, String> TRANSITION_SQL = transitionSql();

    private static Map<TokenEventType, String> transitionSql() {
        Map<TokenEventType, String> sql = new EnumMap<>(TokenEventType.class);
        sql.put(TokenEventType.ADMITTED, TRANSITION_INSERT + """
                admit_token = IF(tokens.status = 0, new.admit_token, tokens.admit_token),
                admitted_at = IF(tokens.status = 0, new.admitted_at, tokens.admitted_at),
                status      = IF(tokens.status = 0, 1, tokens.status)""");
        // 🔴 completed_at을 **여기서 찍는다.** 예전엔 "complete API가 동기 UPDATE로 이미 채운
        //    값"이라 안 건드렸는데, verify가 완료를 확정하게 되면서 complete API를 거치지 않고
        //    status가 2가 되는 경로가 생겼다. 안 채우면 그 행의 completed_at이 영원히 NULL이라
        //      ① complete의 findCompletedAt이 빈 값을 읽어 정상 Tenant가 404를 받고
        //      ② schema.sql의 AVG/MAX(TIMESTAMPDIFF(SECOND, issued_at, completed_at))에서 통째로 빠진다
        //
        //    값을 이벤트로 실어오지 않고 UTC_TIMESTAMP(3)을 쓰는 이유는 둘이다.
        //      · EnqueueEvent에 필드를 더하면 생성 지점 15곳 + Token.reconstruct 5곳 + INSERT 컬럼
        //        + 바인더 + 컨슈머 매핑으로 번진다
        //      · 🪤 ODKU의 SET 절에 '?'를 쓰면 rewriteBatchedStatements가 **조용히 꺼진다**.
        //        함수 호출은 그 문제가 아예 없다
        //    대가는 "verify 응답 시각"이 아니라 "컨슈머 적용 시각"이 되는 것인데(보통 1초 미만),
        //    소비자가 초 단위 일별 집계 하나뿐이라 실질 차이가 없다.
        //
        //    complete API 경로는 영향이 없다 — 동기 UPDATE가 이미 status=2로 만들어 놓아
        //    IF(tokens.status = 1, ...)이 거짓이 되고 자기가 찍은 값이 보존된다.
        sql.put(TokenEventType.COMPLETED, TRANSITION_INSERT + """
                completed_at = IF(tokens.status = 1, UTC_TIMESTAMP(3), tokens.completed_at),
                status       = IF(tokens.status = 1, 2, tokens.status)""");
        // 🔴 출발이 0뿐인 것은 의도다 (§36). admitToken TTL 만료자는 status = 1이라 여기서
        //    no-op이 되고, 그래야 complete의 status IN (0, 1) + 300초 유효 창이 살아남는다.
        //    IN (0, 1)로 넓히면 늦은 입장이 INVALID_ADMIT_TOKEN이 된다.
        sql.put(TokenEventType.EXPIRED, TRANSITION_INSERT +
                "status = IF(tokens.status = 0, 4, tokens.status)");
        return Map.copyOf(sql);
    }

    private final TokenJpaRepository tokenJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    public TokenJpaAdapter (TokenJpaRepository tokenJpaRepository, JdbcTemplate jdbcTemplate) {
        this.tokenJpaRepository = tokenJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }


    @Override
    public void saveAllIfAbsent(List<Token> tokens) {
        if(tokens.isEmpty()) return;

        Map<TokenEntityId, TokenEntity> deduped = new LinkedHashMap<>();
        for(Token token : tokens) {
            TokenEntity entity = TokenEntity.fromDomain(token);
            deduped.putIfAbsent(entity.getId(), entity);
        }

        tokenJpaRepository.saveAll(deduped.values());
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
    public int expireStaleAdmitted(String queueId, LocalDateTime admittedBefore, int limit) {
        return tokenJpaRepository.expireStaleAdmitted(queueId, admittedBefore, limit);
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
