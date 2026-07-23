package com.sonix.queue.infrastructure.entity;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenStatus;
import jakarta.persistence.*;
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

    String queueId;
    Long tenantId;
    String userId;
    long seq;
    int status;

    // WAITING 삽입 시엔 DB 기본값 사용 → INSERT에서 제외 (insertable=false)
    @Column(insertable = false) Integer expiredReason;
    @Column(insertable = false) String  admitToken;
    @Column(insertable = false) boolean redisSyncNeeded;

    @Id
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
