package com.sonix.queue.batch.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
public class TokenEnqueueConsumer {
    private final TokenEnqueueService tokenEnqueueService;

    public TokenEnqueueConsumer(TokenEnqueueService tokenEnqueueService) {
        this.tokenEnqueueService = tokenEnqueueService;
    }

    @KafkaListener(topics = "enqueue-events")
    public void consume(List<EnqueueEvent> events, Acknowledgment ack) {
        List<Token> tokens = events.stream().map(TokenEnqueueConsumer::toToken).toList();

        // 메세지 유실을 방지하기 위해서 Transaction을 분리함 >> DB 저장(커밋) 이후 ack 처리
        tokenEnqueueService.append(tokens);
        ack.acknowledge();

        log.debug("enqueue-events 적재 완료: 수신 {}건", events.size());
    }

    private static Token toToken(EnqueueEvent e) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(e.issuedAt(), ZoneOffset.UTC);
        return Token.issue(e.tokenId(), e.queueId(), e.tenantId(), e.userId(), e.seq(), issuedAt);
    }

}
