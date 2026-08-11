package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.common.util.IdGenerator;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.QueueEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis 기반 대기열 엔진 구현 (Global Queue 배치 방식).
 * <p>단건(hybrid) 분기는 제거되었으며, 모든 요청이 배치로 처리된다.
 *
 * <p><b>Producer-Consumer 패턴:</b>
 * <ul>
 *   <li>Producer (이 클래스의 enqueue): PendingEnqueue를 Global Queue에 offer,
 *       Future.get() 대기</li>
 *   <li>Consumer (BatchProcessor @Scheduled): Global Queue drain,
 *       queueId groupBy, 청크별 Bulk Lua 실행 후 Future.complete()</li>
 * </ul>
 *
 * <p><b>Lua Script 반환 형식:</b>
 * enqueue_bulk.lua: [{identifier, tokenId, status, rank, total, seq, issuedAt}, ...]
 * */

@Slf4j
@Component
public class RedisQueueEngine implements QueueEngine {

    private static final long MAX_WAIT_SECONDS = 30L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> enqueueBulkScript;

    // global queue
    private final ConcurrentLinkedQueue<PendingEnqueue> globalQueue = new ConcurrentLinkedQueue<>();

    public RedisQueueEngine(
            StringRedisTemplate redisTemplate,
            @Qualifier("enqueueBulkScript") RedisScript<List> enqueueBulkScript
    ) {
        this.redisTemplate = redisTemplate;
        this.enqueueBulkScript = enqueueBulkScript;
    }

    @Override
    public EnqueueResult enqueue(String queueId, String identifier) {
        String tokenId = IdGenerator.generate("tok_");
        PendingEnqueue pending = new PendingEnqueue(queueId, identifier, tokenId);

        globalQueue.offer(pending);

        try {
            return pending.getFuture().get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Enqueue timeout after {}s", MAX_WAIT_SECONDS, e);
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        } catch (ExecutionException e) {
            log.error("Enqueue failed", e.getCause());
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        }
    }

    /**
    * enqueue_bulk.lua 단건 결과 파싱: [identifier, tokenId, status, rank, total, seq, issuedAt]
    */
    private EnqueueResult parseEnqueueResult(List<Object> result) {
        if (result == null || result.size() < 7) {
            throw new IllegalStateException("Invalid Lua result: " + result);
        }

        String identifier = (String) result.get(0);
        String tokenId = (String) result.get(1);
        String status = (String) result.get(2);
        long rank = ((Number) result.get(3)).longValue();
        long total = ((Number) result.get(4)).longValue();
        long seq = ((Number) result.get(5)).longValue();
        Instant issuedAt = parseIssuedAt((String) result.get(6));

        return switch (status) {
            case "OK" -> EnqueueResult.ok(identifier, tokenId, rank, total, seq, issuedAt);
            case "EXISTS" -> EnqueueResult.exists(identifier, tokenId, rank, total, seq, issuedAt);
            case "FULL" -> EnqueueResult.full(identifier, total);
            default -> throw new IllegalStateException("Unknown status: " + status);
        };
    }

    private static Instant parseIssuedAt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return Instant.ofEpochMilli(Long.parseLong(raw));
    }

    /**
     * Global Queue 조회 (BatchProcessor가 drain에 사용).
     */
    public ConcurrentLinkedQueue<PendingEnqueue> getGlobalQueue() {
        return globalQueue;
    }

    /**
     * Bulk Lua Script 실행 (BatchProcessor가 사용).
     * 반환 형식: [{identifier, tokenId, status, rank, total, seq, issuedAt}, ...]
     */
    @SuppressWarnings("unchecked")
    public List<Object> executeBulkLua(String queueId, List<PendingEnqueue> batch, long maxCapacity, Instant issuedAt) {
        String queueKey = QueueKeys.waiting(queueId);
        String seqKey = QueueKeys.seq(queueId);
        String tokenKey = QueueKeys.tokens(queueId);

        // ARGV 구성: maxCapacity, count, issuedAt, identifier1, tokenId1, identifier2, tokenId2, ...  (아이템당 2개)
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(maxCapacity));
        args.add(String.valueOf(batch.size()));
        args.add(String.valueOf(issuedAt.toEpochMilli()));
        for (PendingEnqueue pending : batch) {
            args.add(pending.getIdentifier());
            args.add(pending.getTokenId());      // 후보 tokenId (OK일 때만 채택)
        }

        return (List<Object>) redisTemplate.execute(
                enqueueBulkScript,
                List.of(queueKey, seqKey, tokenKey),   // KEYS 세 개: [1]=waiting, [2]=seq, [3]=tokens
                args.toArray()
        );
    }

    /**
     * enqueue_bulk.lua 결과 파싱 (BatchProcessor가 사용).
     * 반환 형식: [{identifier, tokenId, status, rank, total, seq, issuedAt}, ...]
     *
     * <p>결과는 요청한 batch와 <b>같은 순서</b>로 반환된다. enqueue_bulk.lua의 루프는
     * 모든 분기(OK/EXISTS/FULL)에서 정확히 한 건씩 결과를 쌓기 때문이다.
     * identifier는 중복될 수 있으므로(EXISTS가 존재하는 이유) key로 쓰지 말 것.
     */
    @SuppressWarnings("unchecked")
    public List<EnqueueResult> parseBulkResult(List<Object> result) {
        List<EnqueueResult> results = new ArrayList<>(result.size());

        for (Object item : result) {
            results.add(parseEnqueueResult((List<Object>) item));
        }

        return results;
    }

}
