package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.ExpiredAdmit;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueEngine;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.TokenEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * admitToken TTL 만료 → WAITING 복귀 (FRS §10 {@code AdmitTokenExpiryJob} · DECISIONS §36 · §80).
 *
 * <p>Tenant가 admit으로 뽑아갔지만 60초 안에 입장시키지 못한 사람을 <b>원래 seq 그대로</b>
 * 대기열에 되돌린다. 새 seq를 주면 60초를 기다린 사람이 맨 뒤로 밀리므로, 우선순위 보존이
 * 이 잡의 존재 이유다.
 *
 * <p><b>🔴 ShedLock도 분산 락도 쓰지 않는다 — {@code EVAL} 자체가 claim이다 (§80 ⑧).</b>
 * {@code ZRANGEBYSCORE 0 now} + {@code ZREM}이 {@code admit_expire.lua} 한 스크립트 안에 있어
 * Redis 단일 스레드가 둘을 쪼개지 않는다. queue-batch가 3대여도 멤버를 가져가는 것은 한 대뿐이고
 * 나머지는 빈 목록을 받는다. 중복 실행의 대가는 낭비된 {@code EVAL} 한 번이지 중복 복귀가 아니다.
 * 동시성 사다리에서 <b>2단(Redis 원자 연산)이 5단(분산 락)을 이긴다</b> — {@code CLAUDE.md}
 * "{@code @Scheduled} 단독 금지, leader election 필요"의 <b>명시적 예외</b>이며 근거는
 * {@code doc/CONCURRENCY.md} 매트릭스에도 같은 행으로 있다.
 * 리더 선출을 얹으면 락 획득·갱신·만료라는 실패 모드만 새로 생기고 얻는 것이 없다.
 *
 * <p><b>큐 목록은 DB에서 읽는다.</b> Cluster에서 {@code SCAN queue:*:admitted}는 접속한 노드만
 * 훑으므로 다른 마스터에 사는 큐가 <b>조용히</b> 누락된다 — 누락된 토큰은 아무 에러도 없이
 * 영원히 복귀하지 못한다 (§80 ⑧).
 *
 * <p><b>{@code last-active}는 건드리지 않는다</b>(§80 확정). 복귀할 때마다 리셋하면 브라우저를
 * 닫은 사람이 되살아나 영원히 회수되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmitTokenExpiryJob {

    /**
     * 한 큐에서 한 주기에 집어올 최대 건수.
     *
     * <p>Lua가 {@code ZREM}에 {@code unpack}으로 인자를 펴므로 Lua 스택 상한
     * ({@code LUAI_MAXCSTACK} 약 8000) 아래여야 하고, 만료가 몰려도 Redis 단일 스레드를 오래
     * 붙잡으면 같은 노드의 폴링(최대 15만/s)이 함께 밀린다. 남은 몫은 다음 주기(10초)가 가져간다.
     *
     * <p>복귀량이 이 값을 계속 넘으면 만료 처리가 뒤처지므로, 그때 올릴 값이다.
     * {@code admit}의 {@code count} 상한이 100이므로 한 주기에 500이면 admit 5회분이다.
     */
    static final int CLAIM_LIMIT = 500;

    private final QueueRepository queueRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;

    /**
     * 주기 10초 (FRS §10).
     *
     * <p>{@code fixedDelay}인 이유: 큐가 많아 한 바퀴가 10초를 넘으면 {@code fixedRate}는 틱을
     * 겹쳐 쌓는다. 이 잡은 늦어도 되지만 겹치면 안 된다 — 겹쳐도 정합성은 claim이 지키지만
     * Redis 왕복만 배로 늘어난다.
     */
    @Scheduled(fixedDelayString = "${queue.batch.admit-expiry.interval-ms:10000}")
    public void reclaimExpiredAdmits() {
        // Clock 빈을 두지 않는다 — 이 값은 Lua에 넘길 "지금"일 뿐이고, 테스트는 만료 score를
        // 과거로 심어 결과를 결정한다. 시각 주입이 필요해지면 그때 빈을 만든다.
        long now = System.currentTimeMillis();

        int returned = 0;
        for (Queue queue : queueRepository.findAll()) {
            returned += reclaim(queue, now);
        }

        // 0건일 때는 찍지 않는다. 주기 6회/분 × 큐 수만큼의 무의미한 줄이 쌓이면
        // 정작 복귀가 일어난 줄을 찾을 수 없다 (로드 테스트 로그 98%가 한 줄이었던 전례).
        if (returned > 0) {
            log.info("admitToken TTL 만료 복귀 {}건", returned);
        }
    }

    /**
     * 큐 하나를 처리한다. <b>예외를 삼키는 이유</b>: 한 큐(=한 클러스터)의 장애가 나머지 큐의
     * 복귀까지 막으면 안 된다. 다음 주기가 다시 시도하며, 그 사이 만료분은 {@code admitted}
     * ZSet에 그대로 남아 있으므로 유실되지 않는다.
     */
    private int reclaim(Queue queue, long now) {
        String queueId = queue.getQueueId();
        List<ExpiredAdmit> claimed;
        try {
            claimed = queueEngine.claimExpiredAdmits(queueId, now, CLAIM_LIMIT);
        } catch (RuntimeException e) {
            log.error("만료 admit claim 실패 queueId={}", queueId, e);
            return 0;
        }

        for (ExpiredAdmit expired : claimed) {
            publishReturned(queue, expired);
        }
        return claimed.size();
    }

    /**
     * {@code RETURNED} 발행 (key = tokenId).
     *
     * <p><b>{@code admitToken}·{@code admittedAt}은 둘 다 null이다</b> —
     * {@link EnqueueEvent}의 타입별 null 규약 표 그대로다. 소비 측 UPSERT가
     * {@code status = IF(tokens.status = 1, 0, tokens.status)}로 status만 만지므로 나머지 칸은
     * 쓰이지 않는다. 여기서 옛 admitToken을 실어 보내면 이미 무효가 된 값이 DB에 되살아난다.
     *
     * <p><b>발행 실패를 삼킨다.</b> Redis는 이미 커밋됐다 — 대기열에 되돌아갔고
     * {@code admitted}에서도 빠졌다. 되돌릴 수단이 없으므로 재시도해봐야 상태가 나아지지 않는다
     * (admit의 Kafka 발행과 같은 비대칭, §80 Consequences ③).
     * 피해는 {@code tokens.status}가 1에 머무는 것이고, complete가 {@code status IN (0, 1)}로
     * 관대해 이미 흡수하도록 설계돼 있다.
     */
    private void publishReturned(Queue queue, ExpiredAdmit expired) {
        if (!expired.publishable()) {
            // tokens Hash 미스 = tokenId/issuedAt을 모른다. 컨슈머의 멱등 키가
            // (token_id, issued_at)이라 추측해 채우면 같은 토큰의 두 번째 행이 생긴다.
            // 대기열 복귀 자체는 이미 끝났으므로 사용자는 순번을 잃지 않는다.
            log.error("RETURNED 발행 생략(tokenId·issuedAt 미확인) queueId={} identifier={} seq={}",
                    queue.getQueueId(), expired.identifier(), expired.seq());
            return;
        }

        try {
            eventPublisher.publish(new EnqueueEvent(
                    TokenEventType.RETURNED.name(),
                    expired.tokenId(),
                    queue.getQueueId(),
                    queue.getTenantId(),
                    expired.identifier(),
                    expired.seq(),
                    expired.issuedAt(),
                    null, null));
        } catch (RuntimeException e) {
            log.error("RETURNED 발행 실패 tokenId={} queueId={} — 복귀는 이미 확정됐다",
                    expired.tokenId(), queue.getQueueId(), e);
        }
    }
}
