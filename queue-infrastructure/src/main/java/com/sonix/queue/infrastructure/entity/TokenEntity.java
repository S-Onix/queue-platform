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
    @Column(insertable = false) Integer expiredReason;
    @Column(insertable = false, length = 50) String  admitToken;
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
                this.expiredReason, this.admitToken, this.redisSyncNeeded, this.issuedAt);
    }
}
