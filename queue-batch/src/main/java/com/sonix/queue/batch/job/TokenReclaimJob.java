package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.ReclaimedToken;
import com.sonix.queue.domain.queue.ExpiredReason;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenEventType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 이유: 큐에서 사람을 회수하는 배치 — 경로 셋(①admitToken TTL §36 ②inactiveTtl §82 ③waitingTtl).
 * 문제: 회수하지 않으면 그 사람은 재-enqueue 에서 EXISTS(rank -1)를 받아 <b>영구 락아웃</b>된다.
 * 원인: {@code tokens} Hash 필드가 {@code enqueue_bulk.lua} 의 HSETNX 중복 게이트다.
 * 해결: 그 필드를 지운다. 대기열로 <b>되돌리지 않는다</b>(§36). 셋을 한 잡에 둬 큐 목록 조회를 아낀다.
 * 🔴 ShedLock·분산 락을 쓰지 않는다 — 조회와 삭제가 한 EVAL 이라 그 자체가 선점(claim)이다(§80 ⑧). 큐 목록은 DB 에서 읽는다.
 *
 * @author sonix
 */
@Slf4j
@Component
public class TokenReclaimJob {

    /**
     * 이유: 한 큐에서 한 주기에 집어올 최대 건수.
     * 문제 둘: 만료가 몰리면 Redis 단일 스레드를 오래 붙잡아 같은 노드의 폴링이 함께 밀린다.
     *        또 Lua 가 ZREM 에 unpack 으로 인자를 펴므로 Lua 스택 상한(약 8000)을 넘을 수 없다.
     * 해결: 500 으로 끊고 남은 몫은 다음 주기(10초)가 가져간다. admit count 상한 300 의 5회분이다.
     * 🪤 만료량이 이 값을 계속 넘으면 처리가 뒤처지는 것이다 — 그때 올릴 값이다.
     *
     * @author sonix
     */
    static final int CLAIM_LIMIT = 500;

    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;

    /**
     * 이유: 마지막 주기에 관측한 좀비 총합. Gauge 가 이 값을 읽는다.
     * 문제: Gauge 에 Redis 조회를 직접 물리면 Prometheus 주기마다 큐 수만큼 왕복이 생긴다.
     * 원인: Micrometer 는 스크레이프 시점에 함수를 호출하는 pull 방식이다.
     * 해결: 배치가 돌 때 담아 두고 Gauge 는 그 값만 읽는다.
     * ⚠️ PromQL 에서 {@code sum} 이 아니라 <b>{@code max}</b> 로 봐라 — 3대가 같은 값을 각자 보고한다.
     *
     * @author sonix
     */
    private final AtomicLong orphans = new AtomicLong();

    /**
     * 이유: 직전에 로그로 남긴 좀비 수 — <b>값이 바뀔 때만</b> 찍기 위한 것이다.
     * 문제: 고아는 정리 로직이 없어 스스로 회복되지 않아, 조건이 참인 동안 10초마다 찍힌다.
     * 원인: batch 3대면 하루 2만 줄이 쌓여 다른 큐의 단서를 덮는다.
     * 해결: 추이는 gauge 가 갖고 로그는 전이만 기록한다.
     * 🪤 분산 상태가 아니라 JVM 의 로그 중복 억제다 — "static 상태 금지" 대상이 아니다.
     *
     * @author sonix
     */
    private long lastLoggedOrphans;

    /**
     * 생성자 주입. Lombok을 쓰지 않는 이유는 여기서 <b>gauge를 등록</b>하기 때문이다 —
     * 등록은 한 번이면 되고, 이후로는 위 {@link #orphans}를 갱신하는 것으로 값이 반영된다.
     */
    public TokenReclaimJob(QueueRepository queueRepository,
                           QueueEngine queueEngine,
                           EnqueueEventPublisher eventPublisher,
                           MeterRegistry meterRegistry) {
        this.queueRepository = queueRepository;
        this.queueEngine = queueEngine;
        this.eventPublisher = eventPublisher;
        // queue_waiting_orphans — "지금 몇 명인가"라 counter가 아니라 gauge다.
        // 알람은 임계 없이 `max(queue_waiting_orphans) > 0` 하나면 된다.
        meterRegistry.gauge("queue.waiting.orphans", orphans);
    }

    /**
     * 이유: 주기 10초(FRS §10). 설정 키가 {@code reclaim} 인 이유 — admit 만료와 inactive 이탈을 <b>둘 다</b> 회수한다.
     * 문제: {@code fixedRate} 는 한 바퀴가 10초를 넘으면 틱을 겹쳐 쌓는다.
     *       겹쳐도 정합성은 Lua 의 선점(claim)이 지키지만, Redis 왕복이 두 배가 된다.
     * 해결: {@code fixedDelay}. 키를 admit-expiry 로 두면 "admit 만 늦춘다"고 오해해 이탈 회수까지 늦춘다.
     *
     * @author sonix
     */
    @Scheduled(fixedDelayString = "${queue.batch.reclaim.interval-ms:10000}")
    public void reclaim() {
        // Clock 빈을 두지 않는다 — 이 값은 Lua에 넘길 "지금"일 뿐이고, 테스트는 만료 score를
        // 과거로 심어 결과를 결정한다. 시각 주입이 필요해지면 그때 빈을 만든다.
        long now = System.currentTimeMillis();

        int admitExpired = 0;
        int inactive = 0;
        int waitingExpired = 0;
        long orphanTotal = 0;
        List<String> orphanQueues = new ArrayList<>();
        for (Queue queue : queueRepository.findAll()) {
            admitExpired += reclaimExpiredAdmits(queue, now);
            inactive += reclaimInactive(queue, now);
            waitingExpired += reclaimExpiredWaiting(queue, now);

            long orphanCount = countOrphans(queue);
            if (orphanCount > 0) {
                orphanQueues.add(queue.getQueueId() + "=" + orphanCount);
            }
            orphanTotal += orphanCount;
        }
        orphans.set(orphanTotal);
        logOrphanTransition(orphanTotal, orphanQueues);

        // 0건일 때는 찍지 않는다. 주기 6회/분 × 큐 수만큼의 무의미한 줄이 쌓이면
        // 정작 회수가 일어난 줄을 찾을 수 없다 (로드 테스트 로그 98%가 한 줄이었던 전례).
        if (admitExpired > 0 || inactive > 0 || waitingExpired > 0) {
            log.info("회수 admitTokenTTL={}건 inactiveTTL={}건 waitingTTL={}건",
                    admitExpired, inactive, waitingExpired);
        }
    }

    /**
     * 이유: 좀비(고아) 대기자를 <b>세기만</b> 한다 — 아무것도 지우지 않는다 (§80 U9).
     * 문제: 정리 로직을 붙이면 실제로 생기는지도 모르는 것에 삭제 권한을 주게 된다.
     * 원인: 정상 경로(ZREM → HDEL)에서는 안 생기고 Redis 부분 유실·eviction 에서만 생긴다.
     * 해결: 관측만 한다. 판정 근거는 {@link QueueEngine#countOrphanedWaiting} 에 있다.
     * ⚠️ 예외를 삼키면 그 큐는 0으로 집계돼 <b>총합이 조용히 내려간다</b>(더 건강해 보인다).
     *
     * @author sonix
     */
    private long countOrphans(Queue queue) {
        try {
            return queueEngine.countOrphanedWaiting(queue.getQueueId());
        } catch (RuntimeException e) {
            log.error("좀비 관측 실패 queueId={}", queue.getQueueId(), e);
            return 0;
        }
    }

    /**
     * 좀비 수가 <b>바뀐 순간에만</b> 찍는다. 이유는 {@link #lastLoggedOrphans} 참조.
     *
     * <p>0으로 돌아온 것도 한 번은 찍는다 — 알람이 꺼진 근거가 로그에 남아야 "고쳐진 것"과
     * "관측이 죽은 것"을 나중에 구분할 수 있다.
     */
    private void logOrphanTransition(long total, List<String> queues) {
        if (total == lastLoggedOrphans) {
            return;
        }
        if (total > 0) {
            // 🔴 문구에 watermark를 쓰지 마라. 판정은 RedisQueueEngine.countOrphanedWaiting이고
            //    기준은 "waiting에 있는데 tokens Hash에 없다"다. **위치(watermark 비교) 판정은
            //    오탐 15,144건으로 기각된 안**이라, 그렇게 적으면 조사자가 엉뚱한 데를 판다.
            log.warn("좀비 대기자 {}건 — waiting 맨 앞인데 tokens Hash에 없다 {}", total, queues);
        } else {
            log.info("좀비 대기자 0건으로 회복");
        }
        lastLoggedOrphans = total;
    }

    /**
     * 이유: {@code waitingTtl}(절대 만료)을 넘긴 대기자를 회수한다 (FRS §10).
     * 문제: enqueue 만 하고 첫 폴링 전에 떠난 사람은 {@link #reclaimInactive} 가 영영 못 본다.
     * 원인: {@code last-active} 에 멤버가 없다. 실측 재현된 §82 구멍 ③이다(2026-08-24).
     * 해결: 앞부분을 훑어 만료를 판정한다 — 이 경로가 <b>마지노선</b>이다.
     * 🪤 여기서 {@code CLAIM_LIMIT} 은 <b>검사할</b> 최대 건수다(회수 건수가 아니다).
     *
     * @author sonix
     */
    private int reclaimExpiredWaiting(Queue queue, long now) {
        String queueId = queue.getQueueId();
        long cutoff = now - queue.getWaitingTtl() * 1000L;
        List<ReclaimedToken> claimed;
        try {
            claimed = queueEngine.claimExpiredWaiting(queueId, cutoff, CLAIM_LIMIT);
        } catch (RuntimeException e) {
            log.error("waitingTtl 회수 claim 실패 queueId={}", queueId, e);
            return 0;
        }

        for (ReclaimedToken token : claimed) {
            publishExpired(queue, ExpiredReason.WAITING_TTL, token);
        }
        return claimed.size();
    }

    /**
     * 이유: {@code inactiveTtl} 이 지나도록 폴링이 없는 대기자를 회수한다 (§82).
     * 문제: 예외를 삼키지만 <b>재시도가 성립하는 범위가 좁다</b>.
     * 원인: EVAL 이 <b>도달 못 했을 때만</b> 대상이 {@code last-active} 에 남아 다음 주기가 집는다.
     *       EVAL 은 성공했는데 응답만 유실되면(read timeout) 멤버는 이미 빠져 집을 대상이 없다.
     * 해결: 한계를 안고 간다({@code admit_expire} 도 같은 구조다). cutoff 는 큐마다 Java 가 계산한다.
     *
     * @author sonix
     */
    private int reclaimInactive(Queue queue, long now) {
        String queueId = queue.getQueueId();
        long cutoff = now - queue.getInactiveTtl() * 1000L;
        List<ReclaimedToken> claimed;
        try {
            claimed = queueEngine.claimInactive(queueId, cutoff, CLAIM_LIMIT);
        } catch (RuntimeException e) {
            log.error("inactive 회수 claim 실패 queueId={}", queueId, e);
            return 0;
        }

        for (ReclaimedToken token : claimed) {
            publishExpired(queue, ExpiredReason.INACTIVE, token);
        }
        return claimed.size();
    }

    /**
     * 큐 하나를 처리한다. <b>예외를 삼키는 이유</b>: 한 큐(=한 클러스터)의 장애가 나머지 큐의
     * 회수까지 막으면 안 된다. 다음 주기가 다시 시도하며, 그 사이 만료분은 {@code admitted}
     * ZSet에 그대로 남아 있으므로 유실되지 않는다.
     */
    private int reclaimExpiredAdmits(Queue queue, long now) {
        String queueId = queue.getQueueId();
        List<ReclaimedToken> claimed;
        try {
            claimed = queueEngine.claimExpiredAdmits(queueId, now, CLAIM_LIMIT);
        } catch (RuntimeException e) {
            log.error("만료 admit claim 실패 queueId={}", queueId, e);
            return 0;
        }

        for (ReclaimedToken expired : claimed) {
            publishExpired(queue, ExpiredReason.ADMIT_TTL, expired);
        }
        return claimed.size();
    }

    /**
     * 이유: {@code EXPIRED} 를 발행한다(key = tokenId). {@code admitToken}·{@code admittedAt} 은 둘 다 null 이다.
     * 문제: 🔴 <b>호출자마다 효과가 다르다</b> — 소비 가드가 {@code IF(status = 0, 4, status)} 라서다.
     * 원인: inactive·waitingTtl 은 DB 가 0 이라 적용되고, admit 만료분은 DB 가 1 이라 no-op 이다(§36).
     * 🔴 컨슈머 랙으로 DB 가 0 에 머문 사이 도착하면 0→4 가 적용돼 원장이 깨진다(실측 259건, 미결).
     * 🪤 발행 실패는 삼킨다 — Redis 는 이미 커밋돼 되돌릴 수단이 없다(§80 Consequences ③).
     *
     * @author sonix
     */
    private void publishExpired(Queue queue, ExpiredReason reason, ReclaimedToken expired) {
        if (!expired.publishable()) {
            // tokens Hash 미스 = tokenId/issuedAt을 모른다. 컨슈머의 멱등 키가
            // (token_id, issued_at)이라 추측해 채우면 같은 토큰의 두 번째 행이 생긴다.
            // 게이트 해제(HDEL)는 Lua에서 이미 끝났으므로 사용자의 재-enqueue는 막히지 않는다.
            log.error("EXPIRED 발행 생략(tokenId·issuedAt 미확인) queueId={} identifier={} seq={} reason={}",
                    queue.getQueueId(), expired.identifier(), expired.seq(), reason);
            return;
        }

        try {
            eventPublisher.publish(new EnqueueEvent(
                    TokenEventType.EXPIRED.name(),
                    expired.tokenId(),
                    queue.getQueueId(),
                    queue.getTenantId(),
                    expired.identifier(),
                    expired.seq(),
                    expired.issuedAt(),
                    null, null, reason.getCode()));
        } catch (RuntimeException e) {
            log.error("EXPIRED 발행 실패 tokenId={} queueId={} — Redis 회수는 이미 확정됐다",
                    expired.tokenId(), queue.getQueueId(), e);
        }
    }
}
