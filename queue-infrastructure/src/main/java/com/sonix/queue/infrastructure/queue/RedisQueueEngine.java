package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.common.util.IdGenerator;
import com.sonix.queue.domain.queue.AdmitRef;
import com.sonix.queue.domain.queue.AdmitResult;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.ReclaimedToken;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.PacingTier;
import com.sonix.queue.domain.queue.QueueBoard;
import com.sonix.queue.infrastructure.repository.QueueJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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

    /** admitToken 유효 시간 (FRS §6.4). admit-by-* 키의 PX이자 admitted ZSet score의 기준. */
    private static final long ADMIT_TTL_MILLIS = 60_000L;

    /**
     * {@code admit-by-admit} 값이 신 포맷인지 가르는 issuedAt 하한 (2020-01-01T00:00:00Z).
     *
     * <p>구 포맷 {@code "tokenId|identifier"}의 identifier가 {@code "12|34|56"} 같은 모양이면
     * 조각 수도 4개고 숫자 파싱도 성공해서 <b>신 포맷과 구분되지 않는다</b>. 그때 issuedAt이
     * 1970년으로 잡히고, 멱등 키 {@code (token_id, issued_at)}가 어긋나 <b>중복 행 = 중복 청구</b>가 된다.
     *
     * <p>실제 issuedAt은 이 서비스가 존재하기 시작한 뒤의 값이고 <b>시간은 앞으로만 가므로</b>,
     * 하한보다 작은 값은 신 포맷일 수 없다. 오분류가 원리적으로 생기지 않는 기준이다.
     */
    private static final long MIN_PLAUSIBLE_ISSUED_AT_MILLIS = 1_577_836_800_000L;

    /** admit 멱등 payload 보관 시간. Tenant 재시도가 끝났을 시점 이후로 잡는다 (§80). */
    private static final long ADMIT_IDEM_TTL_MILLIS = 300_000L;

    /** admit.lua에서 admitToken 후보가 시작되는 ARGV 위치 직전까지의 고정 인자 수. */
    private static final int ADMIT_TOKEN_OFFSET = 7;

    private final StringRedisTemplate cluster1;
    private final StringRedisTemplate cluster2;

    /**
     * 큐가 어느 클러스터에 배정됐는지의 <b>영속 기록</b>(queues.redis_cluster_no).
     * Redis에 아직 키가 하나도 없는 큐(=생성 후 첫 enqueue)의 목적지를 정할 때만 읽는다.
     *
     * <p>단일 클러스터 구성(3인자 생성자)에서는 {@code null}이다 — 그 구성에서는 라우팅
     * 자체가 없어 이 조회에 도달하지 않는다.
     */
    private final QueueJpaRepository queueJpaRepository;

    /**
     * queueId → 소유 클러스터. <b>WAS 로컬 관찰 메모지 두 번째 진실이 아니다.</b>
     *
     * <p>서버마다 내용이 달라도 무해하다. 큐는 한 번 배정되면 다른 클러스터로 옮기지 않으므로
     * (§75 D27-2) 여기 담기는 값은 <b>불변</b>이다. 각 WAS는 각자 관찰해 같은 정답에 도달하며,
     * 그래서 무효화 로직도 동기화도 필요 없다.
     *
     * <p><b>카디널리티:</b> 요청 수가 아니라 <b>실재하는 큐 수</b>에 비례한다. 소유자를
     * 확인하지 못한 queueId(= 존재하지 않는 큐)는 넣지 않는다 — 넣으면 임의 문자열을 던지는
     * 폴링 하나로 맵을 무한히 부풀릴 수 있다.
     */
    private final Map<String, StringRedisTemplate> ownerByQueueId = new ConcurrentHashMap<>();

    private final RedisScript<List> enqueueBulkScript;
    private final RedisScript<Long> pollVerifyScript;
    private final RedisScript<List> admitScript;
    private final RedisScript<List> admitExpireScript;
    private final RedisScript<List> inactiveExpireScript;
    private final RedisScript<List> waitingExpireScript;

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

    // 생성자가 둘이라 어느 쪽을 쓸지 명시해야 한다. 없으면 Spring이 기본 생성자를 찾다 실패한다.
    @Autowired
    public RedisQueueEngine(
            @Qualifier("stringRedisTemplate") StringRedisTemplate cluster1,
            @Qualifier("cluster2StringRedisTemplate") StringRedisTemplate cluster2,
            QueueJpaRepository queueJpaRepository,
            @Qualifier("enqueueBulkScript") RedisScript<List> enqueueBulkScript,
            @Qualifier("pollVerifyScript") RedisScript<Long> pollVerifyScript,
            @Qualifier("admitScript") RedisScript<List> admitScript,
            @Qualifier("admitExpireScript") RedisScript<List> admitExpireScript,
            @Qualifier("inactiveExpireScript") RedisScript<List> inactiveExpireScript,
            @Qualifier("waitingExpireScript") RedisScript<List> waitingExpireScript
    ) {
        this.cluster1 = cluster1;
        this.cluster2 = cluster2;
        this.queueJpaRepository = queueJpaRepository;
        this.enqueueBulkScript = enqueueBulkScript;
        this.pollVerifyScript = pollVerifyScript;
        this.admitScript = admitScript;
        this.admitExpireScript = admitExpireScript;
        this.inactiveExpireScript = inactiveExpireScript;
        this.waitingExpireScript = waitingExpireScript;
    }

    /**
     * 단일 클러스터 구성용 생성자(테스트 전용).
     *
     * <p>두 참조가 같은 객체이므로 라우팅이 통째로 비활성화된다({@link #route}). 즉 이 생성자로
     * 만든 엔진은 이중화 이전과 <b>완전히 같은 동작</b>이다.
     */
    public RedisQueueEngine(
            StringRedisTemplate redisTemplate,
            RedisScript<List> enqueueBulkScript,
            RedisScript<Long> pollVerifyScript,
            RedisScript<List> admitScript,
            RedisScript<List> admitExpireScript,
            RedisScript<List> inactiveExpireScript,
            RedisScript<List> waitingExpireScript
    ) {
        this(redisTemplate, redisTemplate, null,
                enqueueBulkScript, pollVerifyScript, admitScript, admitExpireScript,
                inactiveExpireScript, waitingExpireScript);
    }

    /**
     * queueId → 소유 클러스터 (§75 이중 라우팅, 안 a″).
     *
     * <ol>
     *   <li>맵 hit → 그대로 사용</li>
     *   <li>miss → 두 클러스터에 {@code EXISTS queue:&#123;queueId&#125;:seq} → 응답한 쪽이 소유자</li>
     *   <li>둘 다 없음 → {@code fallbackForNewQueue}가 정한다</li>
     * </ol>
     *
     * <p><b>미스 비용은 (WAS, queueId)당 평생 1회</b>다. seq 키는 INCR로만 만들어지고
     * 어디서도 지우지 않으므로, 한 번 enqueue된 큐는 비어도 계속 소유권을 증명한다.
     *
     * <p><b>오배송이 안전한 이유(읽기 경로):</b> {@code poll_verify.lua}는 대조에 실패하면
     * {@code return 0}으로 끝나 <b>아무것도 쓰지 않는다</b>. {@code ZADD last-active}는
     * 소유권 대조를 통과한 뒤에만 실행된다. {@code readStatus}도 읽기뿐이다.
     * 그래서 최악의 결과가 "빈 결과 1회"이며, 상태가 갈라지지 않는다.
     *
     * @param fallbackForNewQueue 양쪽 모두 키가 없을 때의 목적지 결정. 읽기는 cluster1로
     *                            떨어뜨려도 무해하지만(위 참조), 쓰기는 DB 배정 기록을 따라야 한다.
     */
    private StringRedisTemplate route(String queueId, Supplier<StringRedisTemplate> fallbackForNewQueue) {
        // 단일 클러스터 구성이면 라우팅할 대상이 없다. 불필요한 EXISTS 왕복도 하지 않는다.
        if (cluster1 == cluster2) {
            return cluster1;
        }

        StringRedisTemplate cached = ownerByQueueId.get(queueId);
        if (cached != null) {
            return cached;
        }

        String seqKey = QueueKeys.seq(queueId);
        StringRedisTemplate owner;
        if (probe(cluster1, seqKey, "cluster1")) {
            owner = cluster1;
        } else if (probe(cluster2, seqKey, "cluster2")) {
            owner = cluster2;
        } else {
            owner = fallbackForNewQueue.get();
        }

        if (owner != null) {
            ownerByQueueId.put(queueId, owner);
            return owner;
        }
        // 소유자를 확정하지 못했다 = 존재하지 않는 큐. 맵에 넣지 않는다(카디널리티 방어).
        return cluster1;
    }

    /**
     * 한 클러스터에 소유권을 묻는다. <b>실패는 "소유자 아님"이 아니라 "모름"이다.</b>
     *
     * <p><b>왜 예외를 잡는가:</b> 이 검사가 예외를 그대로 올리면 <b>cluster1의 장애가 cluster2
     * 소유 큐를 죽인다.</b> 맵이 빈 WAS(재기동·신규 인스턴스·처음 보는 큐)에서 cluster1의 해당
     * 슬롯 마스터가 죽거나 failover 중이면, cluster2 프로브에 도달조차 못 하고
     * {@code COMMAND_TIMEOUT}(5s) 뒤 실패한다. 맵에 기록도 안 되니 cluster1이 회복될 때까지
     * <b>매 요청이 5초를 태운다.</b> (a″)를 고른 이유가 장애 격리인데 여기서 새면 안 된다.
     *
     * <p><b>false를 돌려도 잘못된 소유권이 기록되지 않는다:</b> 이 값이 false면 호출자는
     * 다음 후보를 보고, 아무도 답하지 못하면 폴백으로 간다. 읽기 폴백은 {@code null}이라
     * 맵에 아무것도 남기지 않고(다음 요청이 다시 묻는다), 쓰기 폴백은 Redis가 아니라
     * <b>DB의 배정 기록</b>이라 Redis 장애와 무관하게 정답을 낸다.
     */
    private static boolean probe(StringRedisTemplate redis, String seqKey, String label) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(seqKey));
        } catch (RuntimeException e) {
            // 미스 경로에서만 도달한다(캐시된 큐는 여기 오지 않는다). 실제 장애면 뒤이은
            // 본 명령이 어차피 예외로 드러나므로 여기서는 진단 흔적만 남긴다.
            log.debug("Ownership probe failed on {} for {}: {}", label, seqKey, e.toString());
            return false;
        }
    }

    /** 관찰 메모에 담긴 큐 수. 테스트가 카디널리티 방어를 확인하는 용도이며 운영 로직은 쓰지 않는다. */
    int ownerCacheSize() {
        return ownerByQueueId.size();
    }

    /**
     * 읽기 경로: 소유자를 못 찾으면 관찰 메모에 <b>기록하지 않고</b> cluster1에서 읽는다.
     *
     * <p><b>이 폴백의 결과는 빈 결과가 아니라 404다.</b> cluster2 소유 큐인데 프로브가 실패하면
     * (해당 슬롯 failover 중 + 이 WAS가 그 큐를 아직 캐시 안 함) cluster1의 빈 키를 읽어
     * {@code verifyWaiting}이 false가 되고, {@code QueueEngineService}가 이를
     * {@code TOKEN_NOT_FOUND}(404)로 바꾼다. 클라이언트에게 404는 <b>재시도가 아니라 종료 신호</b>다.
     * 같은 순간 캐시가 데워진 WAS는 예외를 그대로 올려 5xx를 내므로, 같은 사용자가 어느 WAS에
     * 붙느냐로 응답이 갈린다.
     *
     * <p>상태가 갈라지지는 않는다 — {@code poll_verify}는 불일치 시 아무것도 쓰지 않는다.
     * 그리고 이 분기는 <b>cluster2에 큐가 실제로 배정된 뒤에만</b> 발현한다. 동작을 바꿀지는
     * Sprint 7 admit이 새 읽기 경로를 추가할 때 함께 결정한다.
     */
    private StringRedisTemplate routeForRead(String queueId) {
        return route(queueId, () -> null);
    }

    /**
     * 쓰기 경로: 소유자를 못 찾으면 <b>DB의 배정 기록</b>을 따른다.
     *
     * <p>여기서 cluster1로 기본값을 주면 cluster2에 배정된 큐의 <b>첫 enqueue</b>가 cluster1에
     * 키를 만들어버려 배정이 통째로 무의미해진다. 반대로 읽기 경로에 이 조회를 달면,
     * 인증 없는 폴링(최대 15만/s)에 임의 queueId를 섞는 것만으로 DB 조회를 유발할 수 있다.
     * <b>그래서 쓰기 경로에만 있다.</b> 쓰기 경로는 호출 전에 큐 존재가 이미 확인된 상태다
     * ({@code QueueEngineService.enqueue} → {@code findQueueAndVerifyOwner},
     * {@code BatchProcessor.getMaxCapacity}).
     */
    private StringRedisTemplate routeForWrite(String queueId) {
        return route(queueId, () -> {
            if (queueJpaRepository == null) {
                return null;
            }
            return queueJpaRepository.findRedisClusterNoByQueueId(queueId)
                    .map(no -> no == 2 ? cluster2 : cluster1)
                    .orElse(null);
        });
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
    public Optional<QueueBoard> readStatus(String queueId) {
        // 세 키가 같은 {queueId} 해시태그 = 같은 슬롯이라 MGET 한 번이 1왕복이다.
        // ⚠️ routeForRead 필수. routeForWrite를 쓰면 소유자 미확인 시 DB를 조회하는데,
        //    이 경로는 인증이 없어(§79) 임의 queueId를 던지는 것만으로 MySQL을 때울 수 있다.
        List<String> values = routeForRead(queueId).opsForValue().multiGet(List.of(
                QueueKeys.admitWatermark(queueId),
                QueueKeys.pacing(queueId),
                QueueKeys.seq(queueId)));

        if (values == null || values.size() < 3) {
            return Optional.empty();
        }

        String watermark = values.get(0);
        String pacing = values.get(1);
        String seq = values.get(2);

        // seq도 watermark도 없다 = 이 큐에 아무도 들어온 적이 없다 → 404 (§79 D3).
        // watermark만 있고 seq가 없는 경우는 Redis 유실 복구 중일 수 있으므로 실재로 본다.
        if (seq == null && watermark == null) {
            return Optional.empty();
        }

        return Optional.of(new QueueBoard(
                watermark == null ? 0L : Long.parseLong(watermark),
                PacingTier.parse(pacing)));
    }

    @Override
    public boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }

        // poll_verify.lua: seq -> identifier -> 저장된 tokenId 대조, 통과 시에만 last-active 갱신.
        // 검증과 갱신을 한 스크립트에 묶어야 그 사이 이탈한 항목을 되살리지 않는다.
        //
        // ⚠️ KEYS는 최소 1개를 반드시 넘긴다. 비우면 Lettuce가 EVAL을 보낼 노드를 슬롯이 아니라
        //    시드 노드로 고르므로, 4 master 중 3대에서 "Lua script attempted to access a
        //    non local key"로 실패한다(= 3/4 확률로 죽는다).
        Long result = routeForRead(queueId).execute(
                pollVerifyScript,
                List.of(QueueKeys.waiting(queueId), QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId)),
                Long.toString(seq),
                tokenId,
                keepalive ? "1" : "0",
                Long.toString(nowMillis)
        );

        return result != null && result == 1L;
    }

    @Override
    public AdmitResult admit(String queueId, String requestId, int count, long nowMillis) {
        // ARGV: count, expiresAt, admitTtlMs, idemKey(완성), idemTtlMs, byTokenPrefix, byAdmitPrefix, admitToken×N
        List<String> args = new ArrayList<>(ADMIT_TOKEN_OFFSET + count);
        args.add(Integer.toString(count));
        args.add(Long.toString(nowMillis + ADMIT_TTL_MILLIS));
        args.add(Long.toString(ADMIT_TTL_MILLIS));
        args.add(QueueKeys.admitIdem(queueId, requestId));
        args.add(Long.toString(ADMIT_IDEM_TTL_MILLIS));
        args.add(QueueKeys.admitByTokenPrefix(queueId));
        args.add(QueueKeys.admitByAdmitPrefix(queueId));
        // admitToken 후보를 미리 count개 만든다. HGET 미스로 되돌아간 사람 몫은 그냥 버려진다 —
        // Lua가 채택할 때만 쓰기 때문이고, 스크립트 안에서 만들면 EVAL이 비결정적이 된다.
        for (int i = 0; i < count; i++) {
            args.add(IdGenerator.generate("adm_"));
        }

        // ⚠️ routeForWrite 필수. redisTemplate을 직접 쓰면 cluster2에 배정된 큐의 admit이 cluster1로
        //    가서 빈 대기열에서 0명을 뽑는다. 단일 클러스터 로컬에서는 무해해 테스트로 안 잡힌다.
        //    쓰기 폴백(DB 배정)을 타는 것도 맞다 — admit은 큐 존재가 이미 확인된 뒤 호출된다.
        @SuppressWarnings("unchecked")
        List<Object> raw = routeForWrite(queueId).execute(
                admitScript,
                List.of(QueueKeys.waiting(queueId), QueueKeys.tokens(queueId),
                        QueueKeys.admitted(queueId), QueueKeys.admitWatermark(queueId)),
                args.toArray()
        );

        return parseAdmitResult(raw);
    }

    @Override
    public Optional<AdmitRef> findAdmitRefByAdmitToken(String queueId, String admitToken) {
        String raw = routeForWrite(queueId).opsForValue().get(QueueKeys.admitByAdmit(queueId, admitToken));
        if (raw == null) {
            return Optional.empty();
        }
        // 값은 "tokenId|seq|issuedAt|identifier"다 (admit.lua).
        // identifier는 Tenant 자유 문자열이라 '|'가 들어올 수 있고 앞 세 값에는 없다 →
        // 앞에서 세 번만 쪼개고 나머지 전부가 identifier다(limit=4).
        //
        // 🔴 **구 포맷을 필드 개수로 가르면 안 된다.** 구 포맷 "tokenId|identifier"의 identifier에도
        //    '|'가 들어올 수 있어 조각이 4개가 될 수 있다. 실측으로 확인된 두 갈래:
        //      "tok_A|user|with|pipes" → 개수만 보면 신 포맷 → 신원이 "pipes"로 잘린다
        //      "tok_A|12|34|56"        → 숫자라 파싱까지 성공 → issuedAt이 1970년이 되고,
        //                                멱등 키 (token_id, issued_at)가 어긋나 **중복 행**이
        //                                INSERT된다. 과금은 행 수라(§82) 그게 곧 중복 청구다
        //
        //    그래서 **값의 타당성까지** 본다. issuedAt은 epoch millis이고 시간은 앞으로만 가므로,
        //    하한보다 작으면 신 포맷일 수 없다. 오분류가 생길 수 없는 기준이다.
        String[] f = raw.split("\\|", 4);
        if (f.length < 2) {
            // "tokenId"만 있던 더 옛날 포맷 — 신원도 모른다. 호출자가 DB 경로로 간다.
            return Optional.of(new AdmitRef(raw, -1L, null, null));
        }
        if (f.length == 4) {
            try {
                long seq = Long.parseLong(f[1]);
                long millis = Long.parseLong(f[2]);
                if (seq >= 0 && millis >= MIN_PLAUSIBLE_ISSUED_AT_MILLIS) {
                    return Optional.of(new AdmitRef(f[0], seq, Instant.ofEpochMilli(millis), f[3]));
                }
            } catch (NumberFormatException ignored) {
                // 숫자가 아니면 구 포맷이다. 아래 폴백이 신원을 온전히 살린다.
            }
        }
        // 구 포맷 "tokenId|identifier" — **첫 '|' 뒤 전부**가 신원이다(구 파서의 규약 그대로).
        // seq·issuedAt이 없어 이벤트는 못 만들지만 verify의 답은 정확하다.
        return Optional.of(new AdmitRef(f[0], -1L, null, raw.substring(raw.indexOf('|') + 1)));
    }

    /**
     * 폴링 전용 역방향 조회. <b>{@code routeForRead}를 쓴다 — {@code routeForWrite}가 아니다.</b>
     *
     * <p>{@code findAdmitRefByAdmitToken}(verify)은 Tenant 인증 뒤의 저빈도 호출이라 쓰기 폴백
     * (DB 배정 조회)을 타도 되지만, 이 메서드는 <b>인증 없는 폴링</b> 경로다. 쓰기 폴백을 달면
     * 임의 queueId를 섞은 요청만으로 DB 조회를 유발할 수 있다({@link #routeForWrite} 주석과 같은 이유).
     *
     * <p>왕복이 추가되는 것은 {@code verifyWaiting}이 false인 경우뿐이다(= admit됐거나 없는 토큰).
     * 대기 중인 정상 폴링(최대 15만/s)은 여기에 도달하지 않는다. 소유 클러스터도 같은 요청의
     * {@code verifyWaiting}이 이미 관찰해 뒀으므로 추가 프로브가 없다.
     */
    @Override
    public Optional<String> findAdmitTokenByTokenId(String queueId, String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                routeForRead(queueId).opsForValue().get(QueueKeys.admitByToken(queueId, tokenId)));
    }

    @Override
    public List<ReclaimedToken> claimExpiredAdmits(String queueId, long nowMillis, int limit) {
        // ⚠️ routeForWrite 필수. 직접 템플릿을 쓰면 cluster2에 배정된 큐의 복귀가 cluster1에서 돌아
        //    빈 admitted ZSet을 보고 **조용히 0건**을 반환한다. 단일 클러스터 로컬에서는 무해해
        //    테스트로 안 잡히고, 만료된 토큰은 아무 에러도 없이 영원히 복귀하지 못한다.
        //    쓰기 폴백(DB 배정 기록)을 타는 것도 맞다 — 큐 목록 자체를 DB에서 읽어왔으므로
        //    호출 시점에 큐 존재가 이미 확인돼 있다.
        @SuppressWarnings("unchecked")
        List<Object> raw = routeForWrite(queueId).execute(
                admitExpireScript,
                // §36: 복귀가 없어져 waiting은 쓰이지 않는다. KEYS는 admitted·tokens 둘뿐이다.
                List.of(QueueKeys.admitted(queueId), QueueKeys.tokens(queueId)),
                Long.toString(nowMillis),
                Integer.toString(limit)
        );

        return toReclaimed(raw);
    }

    @Override
    public List<ReclaimedToken> claimInactive(String queueId, long cutoffMillis, int limit) {
        // ⚠️ routeForWrite 필수 — claimExpiredAdmits와 같은 이유다. 직접 템플릿을 쓰면 cluster2에
        //    배정된 큐의 회수가 cluster1에서 돌아 **조용히 0건**을 반환하고, 이탈자는 아무 에러도
        //    없이 영원히 큐에 남는다. 단일 클러스터 로컬에서는 무해해 테스트로 안 잡힌다.
        @SuppressWarnings("unchecked")
        List<Object> raw = routeForWrite(queueId).execute(
                inactiveExpireScript,
                List.of(QueueKeys.lastActive(queueId), QueueKeys.waiting(queueId), QueueKeys.tokens(queueId)),
                Long.toString(cutoffMillis),
                Integer.toString(limit)
        );
        return toReclaimed(raw);
    }

    /**
     * 좀비 탐지가 한 번에 훑는 {@code waiting} 앞부분 크기.
     *
     * <p>고아는 admit이 지나가지 못해 앞에 쌓이므로 앞만 보면 된다. admit {@code count} 상한이
     * 100이라 그 10배면 한 주기에 새로 생길 수 있는 양을 덮는다. 넘으면 이 값에서 포화하는데,
     * 포화한 시점이면 이미 알람이 울고 남았을 값이라 정확한 숫자가 의미를 갖지 않는다.
     */
    private static final int ORPHAN_HEAD_SCAN = 1000;

    @Override
    public List<ReclaimedToken> claimExpiredWaiting(String queueId, long cutoffMillis, int limit) {
        // ⚠️ routeForWrite 필수 — claimInactive와 같은 이유다. 직접 템플릿을 쓰면 cluster2에
        //    배정된 큐의 회수가 cluster1에서 돌아 **조용히 0건**을 반환하고, 그 사람들은 아무
        //    에러도 없이 영원히 큐에 남는다. 단일 클러스터 로컬에서는 무해해 테스트로 안 잡힌다.
        @SuppressWarnings("unchecked")
        List<Object> raw = routeForWrite(queueId).execute(
                waitingExpireScript,
                List.of(QueueKeys.waiting(queueId), QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId)),
                Long.toString(cutoffMillis),
                Integer.toString(limit)
        );
        return toReclaimed(raw);
    }

    @Override
    public long countOrphanedWaiting(String queueId) {
        // routeForRead다 — 세기만 하므로 쓰기 경로의 DB 조회(배정 기록)를 유발할 이유가 없다.
        StringRedisTemplate redis = routeForRead(queueId);

        // 맨 앞 = score(seq) 오름차순 앞쪽. 고아가 쌓이는 자리다.
        Set<String> head = redis.opsForZSet().range(QueueKeys.waiting(queueId), 0, ORPHAN_HEAD_SCAN - 1);
        if (head == null || head.isEmpty()) {
            return 0L;
        }

        // 🔴 판정은 여기다 — waiting에 있는데 tokens Hash에 없으면 admit.lua가 되돌리는 고아다.
        //    위치(watermark 비교)로 판정하면 안 된다. 포트 Javadoc의 실측 기각 사유 참조.
        List<Object> stored = redis.opsForHash().multiGet(QueueKeys.tokens(queueId), List.copyOf(head));
        if (stored == null) {
            return 0L;
        }
        return stored.stream().filter(Objects::isNull).count();
    }

    /** claim 계열 두 스크립트의 반환 형식이 같다(원소 4개 고정) — 파싱을 공유한다. */
    private List<ReclaimedToken> toReclaimed(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ReclaimedToken> claimed = new ArrayList<>(raw.size());
        for (Object row : raw) {
            @SuppressWarnings("unchecked")
            List<String> f = (List<String>) row;
            // tokens Hash 미스는 빈 문자열로 온다(nil이면 배열 뒤가 잘린다). null로 바꿔
            // 호출자가 "발행 불가"로 읽게 한다.
            String tokenId = f.get(2).isEmpty() ? null : f.get(2);
            claimed.add(new ReclaimedToken(
                    f.get(0), Long.parseLong(f.get(1)), tokenId, parseIssuedAt(f.get(3))));
        }
        return claimed;
    }

    @Override
    public void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq) {
        StringRedisTemplate redis = routeForWrite(queueId);

        // TTL 만료로 WAITING에 복귀해 있을 수 있다(§6.6 — status 0을 허용하는 것과 같은 이유).
        redis.opsForZSet().remove(QueueKeys.waiting(queueId), identifier);
        redis.opsForZSet().remove(QueueKeys.admitted(queueId), seq + "|" + identifier);
        // 두 키 모두 {queueId} 해시태그라 같은 슬롯이다 — 다중 키 DEL이 Cluster에서도 성립한다.
        redis.delete(List.of(QueueKeys.admitByToken(queueId, tokenId),
                             QueueKeys.admitByAdmit(queueId, admitToken)));

        // 🔴 **반드시 마지막이다.** tokens Hash가 enqueue의 중복 게이트(HSETNX)라, 지우지 않으면
        //    완료한 사람이 다시 들어오지 못한다. 그런데 이 메서드는 Lua가 아니라 명령 4개라
        //    중간에 죽을 수 있고, 순서가 결과를 가른다.
        //      HDEL이 먼저면: Hash만 사라지고 waiting에 남아 poll_verify가 HGET 미스로 계속 0을
        //                     반환한다 → 그 사람은 영영 404다 (복구 경로 없음).
        //      HDEL이 마지막이면: waiting/admitted가 먼저 지워지고 Hash만 남아 재입장만 막힌다
        //                     (다음 complete·TTL 정리로 해소 가능). 안전한 쪽이다.
        //    "정리 순서 통일" 같은 이유로 위로 올리지 말 것.
        redis.opsForHash().delete(QueueKeys.tokens(queueId), identifier);
    }

    /**
     * admit.lua 결과 파싱:
     * {@code { "OK"|"REPLAY", { {identifier, tokenId, seq, admitToken, issuedAt}, ... } }}
     *
     * <p>seq·issuedAt은 두 경로 모두 <b>문자열</b>이다. OK는 ZPOPMIN score와 Hash 값을 문자열
     * 그대로 쓰고(Lua 숫자 포맷 %.14g를 피하려고), REPLAY는 cjson 왕복을 거치는데 문자열은
     * 문자열로 남기 때문이다.
     *
     * <p><b>원소가 4개인 행을 허용하는 이유:</b> 멱등 payload는 Redis에 300초 남는다. 롤링 배포
     * 중에는 <b>이전 버전이 저장한 4개짜리 행</b>이 REPLAY로 돌아올 수 있다. 그 경우 issuedAt은
     * null이고, 호출자가 ADMITTED 발행을 건너뛴다 — 아무 값이나 채우면 컨슈머의 멱등 키가
     * 어긋나 같은 토큰의 두 번째 행이 생긴다.
     */
    @SuppressWarnings("unchecked")
    private static AdmitResult parseAdmitResult(List<Object> raw) {
        if (raw == null || raw.size() < 2) {
            throw new IllegalStateException("Invalid admit Lua result: " + raw);
        }

        boolean replay = "REPLAY".equals(raw.get(0));
        List<Object> rows = (List<Object>) raw.get(1);
        List<AdmitResult.AdmitRecord> records = new ArrayList<>(rows.size());

        for (Object row : rows) {
            List<Object> f = (List<Object>) row;
            records.add(new AdmitResult.AdmitRecord(
                    (String) f.get(0),
                    (String) f.get(1),
                    Long.parseLong((String) f.get(2)),
                    (String) f.get(3),
                    f.size() > 4 ? parseIssuedAt((String) f.get(4)) : null
            ));
        }

        return new AdmitResult(replay, records);
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

        // ⚠️ KEYS는 최소 1개를 반드시 넘긴다(여기선 3개). 비우면 Lettuce가 EVAL을 보낼 노드를
        //    슬롯이 아니라 시드 노드로 고르므로, 4 master 중 3대에서 "Lua script attempted to
        //    access a non local key"로 실패한다. 세 키가 같은 슬롯인 근거는 QueueKeys 참조.
        return (List<Object>) routeForWrite(queueId).execute(
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
