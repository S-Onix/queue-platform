package com.sonix.queue.infrastructure.adapter;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenRepository;
import com.sonix.queue.infrastructure.entity.TokenEntity;
import com.sonix.queue.infrastructure.entity.TokenEntityId;
import com.sonix.queue.infrastructure.repository.TokenJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TokenJpaAdapter implements TokenRepository {

    private final TokenJpaRepository tokenJpaRepository;

    public TokenJpaAdapter (TokenJpaRepository tokenJpaRepository) {
        this.tokenJpaRepository = tokenJpaRepository;
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
}
