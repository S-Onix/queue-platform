package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.common.util.IdGenerator;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final RedisScript<Long> pollVerifyScript;

    // global queue
    private final ConcurrentLinkedQueue<PendingEnqueue> globalQueue = new ConcurrentLinkedQueue<>();

    /**
     * 이 프로세스가 종료 중인지 여부.
     *
     * <p>인스턴스 로컬 상태이며 <b>서버마다 값이 달라도 무해</b>하다. 종료는 인스턴스별로
     * 일어나고, 이 플래그는 "내 Global Queue를 drain해 줄 주체가 아직 있는가"라는
     * 자기 프로세스 한정 질문에만 답한다. 다른 인스턴스는 계속 요청을 받으면 된다.
     */
    private volatile boolean shuttingDown = false;

    public RedisQueueEngine(
            StringRedisTemplate redisTemplate,
            @Qualifier("enqueueBulkScript") RedisScript<List> enqueueBulkScript,
            @Qualifier("pollVerifyScript") RedisScript<Long> pollVerifyScript
    ) {
        this.redisTemplate = redisTemplate;
        this.enqueueBulkScript = enqueueBulkScript;
        this.pollVerifyScript = pollVerifyScript;
    }

    @Override
    public EnqueueResult enqueue(String queueId, String identifier) {
        // fast path. 정합성은 아래 offer→remove 검사가 책임지고, 이 검사는 순전히 비용 방어다.
        // 두 번 검사하는 이유: ConcurrentLinkedQueue.remove()는 O(n)이다. 종료 표시 이후에도
        // 커넥터가 멈추기 전까지 Tomcat은 요청을 계속 받으므로, 백로그가 큰 상태(버스트 시
        // 수만~수십만)에서 모든 요청이 offer→remove를 타면 O(n·m)이 되어 CPU가 포화되고
        // 정작 마지막 drain이 굶는다. 여기서 대부분을 미리 걷어낸다. — 지우지 말 것.
        if (shuttingDown) {
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        }

        String tokenId = IdGenerator.generate("tok_");
        PendingEnqueue pending = new PendingEnqueue(queueId, identifier, tokenId);

        globalQueue.offer(pending);

        // 종료 중이면 이 요청을 처리해 줄 주체가 없다(스케줄러는 ContextClosedEvent에서 이미
        // 멈췄고, BatchProcessor의 마지막 drain도 지나갔을 수 있다). 30초 매달렸다 503이 되느니
        // 즉시 실패시켜 호출자가 다른 인스턴스로 재시도하게 한다.
        //
        // 위의 fast path만으로는 부족하다. 앞 검사만 있으면 "검사 통과 → 마지막 drain 완료 →
        // offer" 순서가 가능해 그 요청이 아무에게도 처리되지 않는다. offer '뒤'에서 다시 보면
        // remove 성공 여부가 곧 "아직 아무도 안 가져갔다"는 증거라 경합 구간이 남지 않는다.
        if (shuttingDown && globalQueue.remove(pending)) {
            throw new BusinessException(ErrorCode.QUEUE_ENGINE_UNAVAILABLE);
        }

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

    @Override
    public QueueSnapshot readSnapshot(String queueId) {
        String waitingKey = QueueKeys.waiting(queueId);

        Set<ZSetOperations.TypedTuple<String>> front = redisTemplate.opsForZSet().rangeWithScores(waitingKey, 0, 0);

        long total = Optional.ofNullable(
                redisTemplate.opsForZSet().zCard(waitingKey)).orElse(0L);

        long frontSeq = -1L;

        if(front != null && !front.isEmpty()) {
            Double score = front.iterator().next().getScore();
            if(score != null) frontSeq = score.longValue();
        }

        return new QueueSnapshot(frontSeq, total);
    }

    @Override
    public boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }

        // poll_verify.lua: seq -> identifier -> 저장된 tokenId 대조, 통과 시에만 last-active 갱신.
        // 검증과 갱신을 한 스크립트에 묶어야 그 사이 이탈한 항목을 되살리지 않는다.
        Long result = redisTemplate.execute(
                pollVerifyScript,
                List.of(QueueKeys.waiting(queueId), QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId)),
                Long.toString(seq),
                tokenId,
                keepalive ? "1" : "0",
                Long.toString(nowMillis)
        );

        return result != null && result == 1L;
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
     * 종료 시작을 알린다 (BatchProcessor의 마지막 drain이 호출).
     *
     * <p>호출 이후 도착하는 enqueue는 대기 없이 실패한다. 되돌리는 경로는 없다 —
     * 컨텍스트가 다시 살아나는 일이 없기 때문이다.
     */
    public void markShuttingDown() {
        this.shuttingDown = true;
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
