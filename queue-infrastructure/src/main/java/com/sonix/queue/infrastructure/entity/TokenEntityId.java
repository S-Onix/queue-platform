package com.sonix.queue.infrastructure.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * TokenEntity의 복합 식별자 (token_id, issued_at).
 *
 * <p>물리 PK인 auto_increment {@code id}를 JPA 식별자로 쓰지 않는 이유:
 * IDENTITY 전략이면 Hibernate가 INSERT를 배치로 묶지 못한다(생성 키를 row마다 즉시 회수).
 * 앱이 이미 들고 있는 자연키(token_id, issued_at)를 식별자로 쓰면 배치 INSERT가 가능하고,
 * 이 키가 곧 멱등 UNIQUE 키와 일치한다.
 */
public class TokenEntityId implements Serializable {
    private String tokenId;
    private LocalDateTime issuedAt;

    protected TokenEntityId() {}

    public TokenEntityId(String tokenId, LocalDateTime issuedAt) {
        this.tokenId = tokenId;
        this.issuedAt = issuedAt;
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof TokenEntityId that)) return false;
        return Objects.equals(tokenId, that.tokenId) && Objects.equals(issuedAt, that.issuedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenId, issuedAt);
    }
}
