package com.sonix.queue.batch.token;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TokenEnqueueService {
    private final TokenRepository tokenRepository;

    public TokenEnqueueService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Transactional
    public void append(List<Token> tokens){
        tokenRepository.saveAllIfAbsent(tokens);
    }
}
