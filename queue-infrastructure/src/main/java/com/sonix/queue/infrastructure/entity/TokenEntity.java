package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.SQLInsert;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;

/**
 * ⚠️ 아래 {@code @SQLInsert}는 §80 가드 표의 <b>{@code ENQUEUED} 한 줄</b>이다(허용 출발: 신규,
 * 충돌 시 no-op). 나머지 다섯 줄은 이벤트마다 SQL이 달라 여기 담을 수 없어
 * {@code TokenJpaAdapter.applyTransition}에 있다 — {@code @SQLInsert}는 엔티티당 한 문장뿐이다.
 *
 * <p>그래서 {@code admit_token}·{@code admitted_at}의 {@code insertable = false}는 그대로 둔다.
 * 이 경로는 WAITING 삽입 전용이라 두 칸에 넣을 값이 애초에 없다.
 */
@Entity
@Table(name = "tokens")
@IdClass(TokenEntityId.class)
@SQLInsert(sql =
        "INSERT INTO tokens (queue_id, seq, status, tenant_id, user_id, issued_at, token_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
        "ON DUPLICATE KEY UPDATE token_id = token_id")
public class TokenEntity implements Persistable<TokenEntityId> {

    // 물리 PK(auto_increment)지만 JPA 식별자 아님 → INSERT엔 미포함, 읽기 전용
    @Column(name = "id", insertable = false, updatable = false)
    Long id;

    // schema.sql이 정의한 폭을 명시한다. 생략하면 Hibernate 기본값(255)이 적용되어
    // ddl-auto: update가 컬럼을 넓혀버리고, validate 환경에서는 기동이 막힌다.
    @Column(length = 50)
    String queueId;
    Long tenantId;
    String userId;
    long seq;
    /**
     * TINYINT 매핑.
     *
     * <p>schema.sql이 TINYINT로 정의한 컬럼이다(값 범위가 좁아 저장공간·비교 성능을 아끼려는
     * 의도적 선택). 자바 int/Integer는 기본적으로 INTEGER로 매핑되므로 명시하지 않으면
     * {@code ddl-auto: validate}가 타입 불일치로 기동을 거부하고, {@code update}는 반대로
     * 컬럼을 INT로 바꿔버려 그 의도를 조용히 되돌린다.
     */
    @JdbcTypeCode(SqlTypes.TINYINT)
    int status;

    // WAITING 삽입 시엔 DB 기본값 사용 → INSERT에서 제외 (insertable=false)
    @JdbcTypeCode(SqlTypes.TINYINT)
    /**
     * 만료 사유({@link com.sonix.queue.domain.queue.ExpiredReason}). {@code EXPIRED}에서만 채워진다.
     *
     * <p>🪤 <b>{@code insertable = false}는 유지한다.</b> "컬럼이 있는데 쓸 수조차 없다"는 지적이
     * 있었지만 절반만 맞다 — 막히는 건 <b>JPA 경로뿐</b>이고, 사유를 쓰는 {@code EXPIRED} 전이는
     * {@code TokenJpaAdapter.TRANSITION_INSERT}(raw JDBC)를 탄다. JPA 경로는 {@code ENQUEUED}
     * 적재라 애초에 사유가 없다.
     *
     * <p>🔴 <b>풀면 {@code @SQLInsert}가 깨진다</b>(실측: {@code Parameter index out of range (8 > 7)}).
     * 그 어노테이션은 컬럼 수가 고정된 SQL 문자열이라 매핑이 바뀌면 바인딩이 어긋난다.
     */
    @Column(insertable = false) Integer expiredReason;
    @Column(insertable = false, length = 50) String  admitToken;
    /**
     * admit 시각 (DECISIONS §80). verify·complete의 유효 창 판정 기준 컬럼이다 —
     * {@code issued_at}("줄 선 시각")이 아니다.
     *
     * <p>매핑이 없어도 {@code ddl-auto: validate}는 <b>여분 DB 컬럼을 통과시킨다.</b> 그래서
     * 지금까지 안 깨졌을 뿐이고, 매핑이 없으면 이 컬럼을 JPQL에서 참조할 수 없다.
     * 쓰기는 ADMITTED 이벤트를 소비하는 컨슈머 UPSERT 몫이라 {@code insertable = false}다.
     */
    @Column(insertable = false) LocalDateTime admittedAt;
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(insertable = false) boolean redisSyncNeeded;

    @Id
    @Column(length = 50)
    String tokenId;
    @Id
    LocalDateTime issuedAt;



    @Nullable
    @Override
    public TokenEntityId getId() {
        return new TokenEntityId(tokenId, issuedAt);
    }

    /** Consumer는 항상 INSERT만 한다 → 무조건 new. 중복은 DB의 ON DUP KEY가 흡수. */
    @Override
    public boolean isNew() {
        return true;
    }

    public static TokenEntity fromDomain(Token token) {
        TokenEntity e = new TokenEntity();
        e.tokenId = token.getTokenId();
        e.queueId = token.getQueueId();
        e.tenantId = token.getTenantId();
        e.userId = token.getUserId();
        e.seq = token.getSeq();
        e.status = token.getStatus().getStatusCode();
        e.issuedAt = token.getIssuedAt();
        return e;
    }

    public Token toDomain() {
        return Token.reconstruct(this.id, this.tokenId, this.queueId, this.tenantId,
                this.userId, this.seq, TokenStatus.fromCode(this.status),
                this.expiredReason, this.admitToken, this.redisSyncNeeded,
                this.issuedAt, this.admittedAt);
    }
}
