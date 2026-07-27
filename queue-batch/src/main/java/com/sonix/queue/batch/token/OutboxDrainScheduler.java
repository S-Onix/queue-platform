package com.sonix.queue.batch.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueOutbox;
import com.sonix.queue.domain.queue.OutboxEntry;
import com.sonix.queue.domain.queue.Token;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Component
public class OutboxDrainScheduler {

    private static final int BATCH = 500;

    private final EnqueueOutbox outbox;                  // 포트 (Redis·JSON 모름)
    private final TokenEnqueueService tokenEnqueueService;

    public OutboxDrainScheduler(EnqueueOutbox outbox, TokenEnqueueService tokenEnqueueService) {
        this.outbox = outbox;
        this.tokenEnqueueService = tokenEnqueueService;
    }

    @Scheduled(fixedDelay = 1000)
    public void drain() {
        process(outbox.inflight());                       // ③ 복구 먼저(크래시 잔여물)
        List<OutboxEntry> claimed = outbox.claim(BATCH);  // ② 정상
        if (!claimed.isEmpty()) process(claimed);
    }

    private void process(List<OutboxEntry> entries) {
        if (entries.isEmpty()) return;
        List<Token> tokens = entries.stream().map(e -> toToken(e.event())).toList();
        tokenEnqueueService.append(tokens);   // 멱등. 실패 시 예외 전파 → 아래 ack 안 됨 → 재처리
        outbox.ack(entries);                  // LREM
        log.debug("outbox 적재: {}건", entries.size());
    }

    private static Token toToken(EnqueueEvent e) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(e.issuedAt(), ZoneOffset.UTC);
        return Token.issue(e.tokenId(), e.queueId(), e.tenantId(), e.userId(), e.seq(), issuedAt);
    }

}
