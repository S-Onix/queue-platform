package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 이유: Redis ↔ DB 정합성 대사(§73 D15 후속). 큐당 두 숫자 — ZCOUNT waiting 과 COUNT status=0.
 * 문제: Redis 와 Kafka 사이엔 분산 트랜잭션이 없어 발행 갭이 영구적이다(100만건에서 835건).
 * 원인: 두 집합은 원래 같아야 한다 — admit 되면 양쪽에서 함께 빠진다. 차이가 곧 갭이다.
 * 해결: 🔴 <b>정착 시간</b>으로 최근 구간을 잘라 낸다 — 없으면 컨슈머 지연이 곧 오탐이다(실측 -500).
 * 🔑 <b>부호가 방향</b>이다: 양수=유령(발행 유실) · 음수=종료 유실. 둘 다 <b>탐지만</b> 한다.
 *
 * @author sonix
 */
@Slf4j
@Component
public class ReconcileJob {

    /**
     * 정착 시간(초). 이보다 최근에 발급된 토큰은 대사에서 제외한다.
     *
     * <p>컨슈머가 이만큼 밀렸다면 그건 정상 지연이 아니라 사고다 — 실측에서 밀린 500건이
     * 40초 만에 해소됐다. 5분은 그 여유를 넉넉히 덮는다.
     */
    static final int SETTLE_SECONDS = 300;

    /**
     * 한 주기에 정리할 최대 행 수.
     *
     * <p>Gap Lock을 피하려고 끊는다({@code doc/ROADMAP.md} DoD). 남은 몫은 다음 주기가 가져간다 —
     * 이 잡은 늦어도 되는 작업이라 한 번에 다 치울 이유가 없다.
     */
    static final int EXPIRE_LIMIT = 100;

    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;
    private final TokenRepository tokenRepository;

    /** 직전 주기의 유령 토큰 수(Redis &gt; DB). ⚠️ PromQL에서 {@code max}로 본다 — claim이 없는 순수 읽기다. */
    private final AtomicLong ghosts = new AtomicLong();

    /** 직전 주기의 낡은 DB 행 수(DB &gt; Redis). 종료 이벤트 유실 신호다. */
    private final AtomicLong stale = new AtomicLong();

    /** 직전에 로그로 남긴 (유령, 낡음). 값이 바뀔 때만 찍기 위한 것이다 — 이유는 TokenReclaimJob과 같다. */
    private long lastLoggedGhosts = -1;
    private long lastLoggedStale = -1;

    public ReconcileJob(QueueRepository queueRepository,
                        QueueEngine queueEngine,
                        TokenRepository tokenRepository,
                        MeterRegistry meterRegistry) {
        this.queueRepository = queueRepository;
        this.queueEngine = queueEngine;
        this.tokenRepository = tokenRepository;
        meterRegistry.gauge("queue.reconcile.ghosts", ghosts);
        meterRegistry.gauge("queue.reconcile.stale", stale);
    }

    /**
     * 주기 5분.
     *
     * <p>회수 배치(10초)와 달리 이 잡은 <b>늦어도 되는 작업</b>이다. 대사가 잡는 것은 이미 벌어진
     * 유실이고, 5분 늦게 안다고 더 나빠지지 않는다. 반대로 자주 돌리면 큐 수만큼의 DB
     * {@code COUNT}가 그만큼 잦아진다.
     */
    @Scheduled(fixedDelayString = "${queue.batch.reconcile.interval-ms:300000}")
    public void reconcile() {
        LocalDateTime settledBefore = nowUtc().minusSeconds(SETTLE_SECONDS);

        long ghostTotal = 0;
        long staleTotal = 0;
        int expired = 0;
        List<String> ghostQueues = new ArrayList<>();
        List<String> staleQueues = new ArrayList<>();

        for (Queue queue : queueRepository.findAll()) {
            expired += expireStaleAdmitted(queue);
            long gap = gapOf(queue, settledBefore);
            if (gap > 0) {
                ghostTotal += gap;
                ghostQueues.add(queue.getQueueId() + "=" + gap);
            } else if (gap < 0) {
                staleTotal += -gap;
                staleQueues.add(queue.getQueueId() + "=" + (-gap));
            }
        }
        ghosts.set(ghostTotal);
        stale.set(staleTotal);
        logTransition(ghostTotal, ghostQueues, staleTotal, staleQueues);

        if (expired > 0) {
            log.info("대사 정리 — complete 창({}초)이 지난 ADMIT_ISSUED {}건을 만료 처리했다",
                    Token.COMPLETE_VALID_WINDOW_SECONDS, expired);
        }
    }

    /**
     * 큐 하나의 갭. <b>양수면 Redis가 많고(유령), 음수면 DB가 많다(종료 이벤트 유실).</b>
     *
     * <p>예외를 삼키는 이유는 회수 배치와 같다 — 한 큐(=한 클러스터)의 장애가 나머지 큐의 대사까지
     * 막을 이유가 없다. 다만 실패한 큐는 <b>갭 0으로 집계된다</b>(못 찾으면 통과). 그 한계는
     * batch의 {@code up} 알람이 먼저 울려야 할 사안이다.
     */
    private long gapOf(Queue queue, LocalDateTime settledBefore) {
        String queueId = queue.getQueueId();
        try {
            long settledSeq = tokenRepository.findSettledMaxSeq(queueId, settledBefore);
            if (settledSeq <= 0) {
                // 정착 구간에 토큰이 없다 = 새 큐이거나 최근에만 유입이 있었다. 대사할 것이 없다.
                return 0;
            }
            return queueEngine.countWaitingUpTo(queueId, settledSeq)
                    - tokenRepository.countWaitingUpTo(queueId, settledSeq);
        } catch (RuntimeException e) {
            log.error("대사 실패 queueId={}", queueId, e);
            return 0;
        }
    }

    /**
     * 이유: complete 유효 창이 지나도록 {@code ADMIT_ISSUED} 에 남은 토큰을 만료로 정리한다.
     * 문제: Tenant 가 verify·complete 를 둘 다 안 부르면 status 가 1에 영원히 남는다.
     * 원인: 회수 배치는 Redis 게이트만 풀고 EXPIRED 가드가 1에서 no-op 이다(늦은 입장을 살리려는 §36).
     * 해결: 이 경로만 <b>직접 UPDATE</b> 한다. 더 일찍 자르면 늦은 통보가 404 다(실측 98초에도 200).
     * 🔴 cutoff 를 여기서 계산하지 마라(§90) — batch 시계로 자르면 완료 가능한 행이 영구 고정된다.
     *
     * @author sonix
     */
    private int expireStaleAdmitted(Queue queue) {
        try {
            return tokenRepository.expireStaleAdmitted(
                    queue.getQueueId(), Token.COMPLETE_VALID_WINDOW_SECONDS, EXPIRE_LIMIT);
        } catch (RuntimeException e) {
            log.error("ADMIT_ISSUED 잔류 정리 실패 queueId={}", queue.getQueueId(), e);
            return 0;
        }
    }

    /**
     * 이유: 배치가 쓰는 "지금". 🔴 <b>반드시 UTC 다</b> — 시각 컬럼이 전부 UTC 다(§77).
     * 문제: 로컬 시각으로 자르면 창이 9시간 어긋난다.
     * 해결: UTC 로 고정한다.
     * 🪤 원장 판정(만료 확정)의 cutoff 로는 쓰지 마라 — 그건 DB 시계가 정한다(§90).
     *
     * @author sonix
     */
    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 값이 바뀔 때만 찍는다. 갭은 스스로 회복되지 않아, 매 주기 찍으면 조사할 때 다른 단서를 덮는다. */
    private void logTransition(long ghostTotal, List<String> ghostQueues,
                               long staleTotal, List<String> staleQueues) {
        if (ghostTotal == lastLoggedGhosts && staleTotal == lastLoggedStale) {
            return;
        }
        if (ghostTotal > 0 || staleTotal > 0) {
            log.warn("대사 갭 — 유령(Redis>DB) {}건 {} / 낡음(DB>Redis) {}건 {}",
                    ghostTotal, ghostQueues, staleTotal, staleQueues);
        } else {
            log.info("대사 갭 0으로 회복");
        }
        lastLoggedGhosts = ghostTotal;
        lastLoggedStale = staleTotal;
    }
}
