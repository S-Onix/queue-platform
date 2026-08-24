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
 * Redis ↔ DB 정합성 대사 (Sprint 9 · §73 D15 후속).
 *
 * <p>§73이 "Redis와 Kafka 사이엔 분산 트랜잭션이 없어 <b>발행 갭은 영구적</b>"이라며 필수 후속으로
 * 남긴 작업이다. 100만건 실측에서 실제로 <b>835건</b>이 "Redis엔 있고 DB엔 없는 유령 토큰"으로 남았다.
 *
 * <h2>대사 방식 — 큐당 두 숫자</h2>
 * <pre>
 *   Redis  ZCOUNT waiting -inf {settledSeq}
 *   DB     COUNT(*) WHERE status = 0 AND seq &lt;= {settledSeq}
 * </pre>
 * {@code waiting} ZSet과 DB {@code status = 0}은 <b>정확히 같은 집합</b>이다 — admit되면 ZSet에서
 * 빠지고 DB에서도 1이 되므로 양쪽에서 함께 빠진다. 그래서 두 수를 그냥 빼면 갭이 나온다.
 *
 * <p><b>비싼 스캔은 갭이 0이 아닐 때만 한다.</b> 평상시 비용은 큐당 {@code ZCOUNT} 1회 +
 * {@code COUNT} 1회다.
 *
 * <h2>🔴 정착 시간(settle window)이 없으면 컨슈머 지연이 곧 오탐이다</h2>
 * 방금 들어온 사람은 Kafka를 타는 중이라 <b>Redis엔 있고 DB엔 없는 게 정상</b>이다.
 * 실측으로 밟았다 — 회수가 도는 중에 앱을 끊자 큐마다 -500이 찍혔고, 컨슈머를 다시 띄우자
 * <b>40초 만에 전부 0</b>이 됐다. 그래서 {@code settledSeq}로 최근 구간을 잘라 낸다.
 *
 * <h2>부호가 방향을 말해 준다 — 그래서 조치도 다르다</h2>
 * <table><caption>대사 결과별 조치</caption>
 *   <tr><th>부호</th><th>의미</th><th>조치</th></tr>
 *   <tr><td><b>양수</b> (Redis &gt; DB)</td><td>유령 토큰 — {@code ENQUEUED} 발행 유실</td>
 *       <td><b>탐지만</b>. 복구(재발행)는 이 값이 0이 아닌 것을 실제로 본 뒤에 붙인다</td></tr>
 *   <tr><td><b>음수</b> (Redis &lt; DB)</td><td>종료 이벤트 유실</td>
 *       <td>🔴 <b>탐지만</b>. Redis 전손과 구분할 수단이 없어 자동 복구가 전원을 만료로 오판할 수 있다</td></tr>
 * </table>
 *
 * <p>세 번째 갈래인 {@link #expireStaleAdmitted()}는 <b>Redis를 아예 보지 않아</b> 그 위험이 없고,
 * 그래서 유일하게 자동 정리를 한다.
 *
 * <h2>ShedLock을 쓰지 않는다</h2>
 * 읽기 두 개는 부수효과가 없어 batch가 N대여도 무해하다(같은 값을 각자 보고할 뿐 —
 * PromQL에서 {@code sum}이 아니라 {@code max}로 본다). 정리 UPDATE는 술어 {@code status = 1}이
 * 멱등성을 만들어 각 행이 한 번만 전이한다.
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
     * {@code complete} 유효 창이 지나도록 {@code ADMIT_ISSUED}에 남은 토큰을 만료로 정리한다.
     *
     * <p>🔑 <b>Tenant가 {@code verify}도 {@code complete}도 안 부른 경우</b>가 여기로 온다.
     * 회수 배치는 Redis 게이트만 풀고 status는 안 건드린다 — {@code EXPIRED} 소비 가드가
     * {@code IF(status = 0, 4, status)}라 1에서는 no-op이고, 그건 늦은 입장을 살리려는 의도다(§36).
     * 그래서 이 경로만은 <b>이벤트가 아니라 직접 UPDATE</b>다.
     *
     * <p><b>왜 {@link Token#COMPLETE_VALID_WINDOW_SECONDS}가 기준인가</b>: 그 창이 지나면
     * {@code markCompleted}가 어차피 0행이라 <b>더 이상 완료가 올 수 없다</b> — 정리해도 되돌릴
     * 것이 없다. 더 일찍(예: admitToken TTL 60초) 자르면 정상적인 늦은 통보가 404를 받는다.
     * 실측으로 확인된 경로다 — admit 후 <b>98초</b>에도 {@code complete}가 200을 돌려준다.
     */
    private int expireStaleAdmitted(Queue queue) {
        LocalDateTime cutoff = nowUtc().minusSeconds(Token.COMPLETE_VALID_WINDOW_SECONDS);
        try {
            return tokenRepository.expireStaleAdmitted(queue.getQueueId(), cutoff, EXPIRE_LIMIT);
        } catch (RuntimeException e) {
            log.error("ADMIT_ISSUED 잔류 정리 실패 queueId={}", queue.getQueueId(), e);
            return 0;
        }
    }

    /**
     * 🔴 <b>반드시 UTC다.</b> 시각 컬럼이 전부 UTC이고(§77) 이 값이 그대로 SQL 술어에 들어간다.
     * {@code LocalDateTime.now()}는 호스트 TZ(개발자 KST)를 쓰므로 9시간 어긋나 —
     * 정리 대상이 아닌 행을 만료시키거나 대사 기준선이 통째로 밀린다.
     *
     * <p>{@code Clock} 빈을 주입하지 않는 이유는 {@link TokenReclaimJob}과 같다. 그 빈은
     * {@code queue-api}의 {@code UtilConfig}에만 있고 <b>queue-batch는 queue-api를 의존하지 않는다</b> —
     * 주입을 걸면 기동 자체가 실패한다. 시각 고정이 필요해지면 그때 이 모듈에 빈을 만든다.
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
