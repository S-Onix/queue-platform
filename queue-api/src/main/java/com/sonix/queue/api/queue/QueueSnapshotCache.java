package com.sonix.queue.api.queue;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueSnapshot;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 큐 스냅샷 WAS-local 캐시 (Caffeine).
 *
 * <p>frontSeq/total은 큐당 공유값이라 폴링마다 Redis 조회하면 낭비 →
 * 2초간 로컬 캐시. lazy 로더: 만료 후 다음 폴링이 올 때만 Redis 재조회
 * (유휴 큐는 0회). 배경 스레드/@Scheduled 없음.
 */
@Component
public class QueueSnapshotCache {

    private final LoadingCache<String, QueueSnapshot> cache;

    public QueueSnapshotCache(QueueEngine queueEngine) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(2))
                .build(queueEngine::readSnapshot);   // 로더 = 포트 호출
    }

    public QueueSnapshot get(String queueId) {
        return cache.get(queueId);
    }

}
