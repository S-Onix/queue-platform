package com.sonix.queue.domain.queue;

import java.util.List;

/** Enqueue outbox 소비 포트 (§72 B — Redis 세부는 infra 어댑터). */
public interface EnqueueOutbox {
    List<OutboxEntry> claim(int max);   // pending→processing LMOVE + 역직렬화
    List<OutboxEntry> inflight();       // processing 잔여물(복구용)
    void ack(List<OutboxEntry> entries);// processing LREM
}