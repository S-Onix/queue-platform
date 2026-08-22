package com.sonix.queue.batch.job;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import com.sonix.queue.domain.queue.ReclaimedToken;
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
 * 큐에서 사람을 회수하는 배치 — <b>두 경로</b> (FRS §10 · DECISIONS §36 · §80 · §82).
 *
 * <ol>
 *   <li><b>admitToken TTL 만료</b>(§36) — Tenant가 뽑아갔는데 60초 안에 입장시키지 못한 사람</li>
 *   <li><b>{@code inactiveTtl} 초과</b>(§82) — 폴링이 끊긴 사람. <b>이탈 회수의 유일한 경로</b>다.
 *       §82가 Cancel API를 폐기해, 유저가 취소 버튼을 누르든 탭을 닫든 네트워크가 끊기든
 *       Platform이 보는 신호는 "폴링이 멈춘다" 하나뿐이다</li>
 * </ol>
 *
 * <p><b>한 잡에 둘을 넣는다.</b> 잡을 나누면 {@code queueRepository.findAll()}이 주기마다
 * 두 번 돈다 — batch 3대면 큐 수 × 12회/분이다. 같은 루프에서 {@code EVAL} 두 번이 싸다.
 *
 * <p>Tenant가 admit으로 뽑아갔지만 60초 안에 입장시키지 못한 사람을 정리한다.
 * <b>🔴 대기열로 되돌리지 않는다 (§36).</b> Platform은 만료 원인(유저 이탈 / 네트워크 / Tenant
 * 지연 / 폴링 수령 지연)을 구분할 수단이 없고, 그 중 유일한 Platform 귀책분(폴링 수령 지연)은
 * 실측상 이미 60초 예산 안이다 — 최악 20초, 소요는 10초 미만. 봐줄 근거가 사라졌으므로 판정하지
 * 않고 그대로 끝낸다.
 *
 * <p><b>이 잡의 존재 이유는 이제 {@code tokens} Hash 필드를 지우는 것이다.</b> 그 필드가
 * {@code enqueue_bulk.lua}의 {@code HSETNX} 중복 게이트이고, 지우는 경로가
 * {@code cleanupCompleted} 하나뿐이라 만료자는 아무도 치워주지 않는다. 안 지우면 그 사람은
 * 재-enqueue에서 {@code EXISTS}(rank -1)를 받아 <b>영구 락아웃</b>된다.
 *
 * <p><b>🔴 ShedLock도 분산 락도 쓰지 않는다 — {@code EVAL} 자체가 claim이다 (§80 ⑧).</b>
 * {@code ZRANGEBYSCORE 0 now} + {@code ZREM}이 {@code admit_expire.lua} 한 스크립트 안에 있어
 * Redis 단일 스레드가 둘을 쪼개지 않는다. queue-batch가 3대여도 멤버를 가져가는 것은 한 대뿐이고
 * 나머지는 빈 목록을 받는다. 중복 실행의 대가는 낭비된 {@code EVAL} 한 번이지 중복 회수가 아니다.
 * 동시성 사다리에서 <b>2단(Redis 원자 연산)이 5단(분산 락)을 이긴다</b> — {@code CLAUDE.md}
 * "{@code @Scheduled} 단독 금지, leader election 필요"의 <b>명시적 예외</b>이며 근거는
 * {@code doc/CONCURRENCY.md} 매트릭스에도 같은 행으로 있다.
 * 리더 선출을 얹으면 락 획득·갱신·만료라는 실패 모드만 새로 생기고 얻는 것이 없다.
 *
 * <p><b>큐 목록은 DB에서 읽는다.</b> Cluster에서 {@code SCAN queue:*:admitted}는 접속한 노드만
 * 훑으므로 다른 마스터에 사는 큐가 <b>조용히</b> 누락된다 — 누락된 토큰은 아무 에러도 없이
 * 영원히 회수되지 못한다 — 그 사람은 재-enqueue도 막힌 채 남는다 (§80 ⑧).
 *
 * <p><b>{@code last-active}는 건드리지 않는다</b>(§80 확정). §36으로 복귀가 사라져 리셋 논점
 * 자체가 없어졌지만, 이 잡이 만료자의 {@code last-active}를 지우지도 않는다 — 그 사람은
 * {@code waiting}에 없으므로 {@code inactiveTtl} sweep(§82)의 대상이 아니고, 남은 멤버는
 * 회수 배치가 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenReclaimJob {

    /**
     * 한 큐에서 한 주기에 집어올 최대 건수.
     *
     * <p>Lua가 {@code ZREM}에 {@code unpack}으로 인자를 펴므로 Lua 스택 상한
     * ({@code LUAI_MAXCSTACK} 약 8000) 아래여야 하고, 만료가 몰려도 Redis 단일 스레드를 오래
     * 붙잡으면 같은 노드의 폴링(최대 15만/s)이 함께 밀린다. 남은 몫은 다음 주기(10초)가 가져간다.
     *
     * <p>만료량이 이 값을 계속 넘으면 처리가 뒤처지므로, 그때 올릴 값이다.
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
    // 키가 reclaim인 이유: 이 잡은 admit 만료와 inactive 이탈 **둘 다** 회수한다.
    //   admit-expiry로 두면 운영자가 "admit만 늦춘다"고 생각하고 값을 키워 이탈 회수까지 늦춘다.
    @Scheduled(fixedDelayString = "${queue.batch.reclaim.interval-ms:10000}")
    public void reclaim() {
        // Clock 빈을 두지 않는다 — 이 값은 Lua에 넘길 "지금"일 뿐이고, 테스트는 만료 score를
        // 과거로 심어 결과를 결정한다. 시각 주입이 필요해지면 그때 빈을 만든다.
        long now = System.currentTimeMillis();

        int admitExpired = 0;
        int inactive = 0;
        for (Queue queue : queueRepository.findAll()) {
            admitExpired += reclaimExpiredAdmits(queue, now);
            inactive += reclaimInactive(queue, now);
        }

        // 0건일 때는 찍지 않는다. 주기 6회/분 × 큐 수만큼의 무의미한 줄이 쌓이면
        // 정작 회수가 일어난 줄을 찾을 수 없다 (로드 테스트 로그 98%가 한 줄이었던 전례).
        if (admitExpired > 0 || inactive > 0) {
            log.info("회수 admitTokenTTL={}건 inactiveTTL={}건", admitExpired, inactive);
        }
    }

    /**
     * {@code inactiveTtl}이 지나도록 폴링이 없는 대기자를 회수한다 (§82).
     *
     * <p><b>cutoff는 큐마다 다르다</b> — {@code inactiveTtl}이 큐 설정이므로
     * ({@code QueueCreateRequest.inactiveTtl}, 기본 300초) Java가 계산해 넘긴다.
     *
     * <p>예외를 삼키는 이유는 아래 {@code reclaimExpiredAdmits}와 같다. 다만 <b>재시도가 성립하는
     * 범위가 좁다</b> — {@code EVAL}이 <b>도달하지 못했을 때만</b> 대상이 {@code last-active}에 남아
     * 다음 주기가 다시 집는다. {@code EVAL}은 성공했는데 <b>응답만 유실된 경우</b>(read timeout ·
     * 커넥션 리셋) 멤버는 이미 세 키에서 다 빠졌고 반환 record도 잃어 <b>다음 주기가 집을 대상이
     * 없다</b>. {@code admit_expire}도 같은 구조적 한계다.
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
            publishExpired(queue, token);
        }
        return claimed.size();
    }

    /**
     * 큐 하나를 처리한다. <b>예외를 삼키는 이유</b>: 한 큐(=한 클러스터)의 장애가 나머지 큐의
     * 복귀까지 막으면 안 된다. 다음 주기가 다시 시도하며, 그 사이 만료분은 {@code admitted}
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
            publishExpired(queue, expired);
        }
        return claimed.size();
    }

    /**
     * {@code EXPIRED} 발행 (key = tokenId).
     *
     * <p><b>🔴 두 호출자에게 효과가 다르다.</b> 소비 측 가드가
     * {@code status = IF(tokens.status = 0, 4, tokens.status)}이기 때문이다.
     *
     * <table><caption>경로별 효과</caption>
     *   <tr><th>호출자</th><th>회수 시점 status</th><th>발행의 효과</th></tr>
     *   <tr><td>{@link #reclaimExpiredAdmits}(§36)</td><td>1 (ADMIT_ISSUED)</td>
     *       <td><b>no-op</b> — 발행에 실패해도 결과가 같다</td></tr>
     *   <tr><td>{@link #reclaimInactive}(§82)</td><td><b>0 (WAITING)</b></td>
     *       <td><b>실제로 0 → 4를 적용한다</b></td></tr>
     * </table>
     *
     * <p>admit 만료분이 {@code 1}에 머무는 것은 <b>의도된 동작이다</b>(§36) — {@code complete}의
     * 술어가 {@code status IN (0, 1)}이고 유효 창이 300초라, admitToken TTL(60초)이 지난 뒤 도착하는
     * <b>늦은 입장이 정상 경로로 실재</b>한다. 가드를 {@code IN (0, 1)}로 넓히면 그 경로가 죽는다.
     *
     * <p><b>{@code admitToken}·{@code admittedAt}은 둘 다 null이다</b> —
     * {@link EnqueueEvent}의 타입별 null 규약 표 그대로다. 여기서 옛 admitToken을 실어 보내면
     * 이미 무효가 된 값이 DB에 되살아난다.
     *
     * <p><b>발행 실패를 삼킨다.</b> Redis는 이미 커밋됐다 — 회수 대상이 키에서 빠졌고
     * {@code tokens} 필드도 지워졌다. 되돌릴 수단이 없으므로 재시도해봐야 상태가 나아지지 않는다
     * (admit의 Kafka 발행과 같은 비대칭, §80 Consequences ③).
     *
     * <p><b>🔴 그러나 삼킴의 대가도 경로마다 다르다.</b> admit 만료분은 위 표대로 발행에 성공해도
     * 결과가 같아 <b>피해가 없다</b>. inactive 회수분은 다르다 — 발행이 유실되면 Redis 세 키에서는
     * 사라졌는데 DB는 <b>영원히 {@code WAITING(0)}</b>으로 남고, Redis에 흔적이 없어
     * <b>reconciliation이 대조할 원본조차 없다</b>. 삼키는 선택은 유지하되(되돌릴 수단이 없다)
     * 이 갭은 Sprint 9 reconciliation의 몫이며 <b>지금은 에러 로그가 유일한 단서</b>다.
     */
    private void publishExpired(Queue queue, ReclaimedToken expired) {
        if (!expired.publishable()) {
            // tokens Hash 미스 = tokenId/issuedAt을 모른다. 컨슈머의 멱등 키가
            // (token_id, issued_at)이라 추측해 채우면 같은 토큰의 두 번째 행이 생긴다.
            // 게이트 해제(HDEL)는 Lua에서 이미 끝났으므로 사용자의 재-enqueue는 막히지 않는다.
            log.error("EXPIRED 발행 생략(tokenId·issuedAt 미확인) queueId={} identifier={} seq={}",
                    queue.getQueueId(), expired.identifier(), expired.seq());
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
                    null, null));
        } catch (RuntimeException e) {
            log.error("EXPIRED 발행 실패 tokenId={} queueId={} — Redis 회수는 이미 확정됐다",
                    expired.tokenId(), queue.getQueueId(), e);
        }
    }
}
