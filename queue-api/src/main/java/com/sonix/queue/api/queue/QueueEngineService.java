package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class QueueEngineService {

    /**
     * admitToken 유효 시간(초). {@code RedisQueueEngine.ADMIT_TTL_MILLIS}(60_000)와 같은 값이며,
     * verify의 DB fallback이 {@code admitted_at} 신선도를 재는 창이다 (FRS §6.4·§6.5).
     */
    private static final int ADMIT_TTL_SECONDS = 60;

    /**
     * complete를 받아 주는 유효 창(초). <b>admitToken TTL 60초보다 반드시 길다</b> —
     * TTL이 만료돼 WAITING으로 복귀했는데 Tenant는 이미 유저를 입장시킨 경우를 덮어야 하기
     * 때문이다(FRS §6.6이 {@code status IN (0,1)}로 관대한 것과 같은 이유).
     *
     * <p><b>왜 300인가</b>: 이 값은 "Tenant가 얼마나 늦어도 봐줄 것인가"라는 SLA 판단이라
     * 시스템 상수에서 유도되지 않는다. 그래서 <b>이미 있는 숫자</b>에 맞췄다 —
     * {@code RedisQueueEngine.ADMIT_IDEM_TTL_MILLIS}(300_000)가 "Tenant 재시도가 끝났을 시점"으로
     * 잡은 값이고, 그 창이 닫힌 뒤 도착한 complete는 대응할 admit 재시도가 이미 없다.
     * 큐 기본 {@code inactiveTtl}(300초)과도 같아 외울 숫자가 하나로 준다.
     *
     * <p>60초에 대해 5배 여유 = TTL 만료 후 복귀·재입장 처리에 4분을 준다는 뜻이다.
     * 늘리는 건 하위호환, 줄이는 건 파괴적 변경이므로 여기서도 "필요를 채우는 최소"를 잡았다.
     */
    private static final int COMPLETE_VALID_WINDOW_SECONDS = 300;

    private final QueueRepository queueRepository;
    private final TokenRepository tokenRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;
    private final Clock clock;                        // 시간 주입(테스트 제어)

    public QueueEngineService(QueueRepository queueRepository, TokenRepository tokenRepository,
                              QueueEngine queueEngine, EnqueueEventPublisher eventPublisher,
                              Clock clock) {
        this.queueRepository = queueRepository;
        this.tokenRepository = tokenRepository;
        this.queueEngine = queueEngine;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 대기열 진입 처리.
     *
     * @param queueId 대기열 외부 식별자 (예: "q_xyz789")
     * @param identifier 사용자 식별자 (Tenant가 자유 지정)
     * @return 진입 결과 (OK 또는 EXISTS)
     * @throws BusinessException queue가 없거나(QUEUE_NOT_FOUND),
     *                           Enqueue 불가 상태(QUEUE_NOT_ENQUEUEABLE)이거나,
     *                           대기열이 가득 찬(QUEUE_FULL) 경우
     */
    public EnqueueResult enqueue(long tenantId, String queueId, String identifier) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        if (!queue.isEnqueueable()) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ACTIVE);
        }

        EnqueueResult result = queueEngine.enqueue(queueId, identifier);

        if (result.isFull()) {
            throw new BusinessException(ErrorCode.QUEUE_FULL);
        }

        if (result.isOk()) {
            eventPublisher.publish(EnqueueEvent.of(tenantId, queueId, result));
        }

        return result;
    }

    /**
     * Admit — 대기열 앞에서 count명을 꺼내 admitToken을 발급한다 (FRS §6.4).
     *
     * <p>{@code admit.lua} 하나로 전 구간이 원자다. 중간에 DB를 보지 않는다 — 순번은 Redis에
     * 먼저 쓰이고 DB에는 Kafka를 거쳐 나중에 들어가므로(§71 D11), 그 창의 정상 대기자를
     * "DB에 없으니 유령"으로 지우면 대기열에서도 빠지고 복구 근거도 사라진다 (§80).
     *
     * <p><b>@Transactional을 붙이지 않는다.</b> DB 쓰기가 없고, 붙이면 Redis EVAL과 Kafka 발행
     * (최대 {@code send-timeout}까지 블록)이 통째로 커넥션을 잡는다.
     */
    public AdmitResult admit(long tenantId, String queueId, int count, String requestId) {
        findQueueAndVerifyOwner(tenantId, queueId);

        long now = clock.millis();
        AdmitResult result = queueEngine.admit(queueId, requestId, count, now);

        publishAdmitted(tenantId, queueId, result, Instant.ofEpochMilli(now));

        return result;
    }

    /**
     * ADMITTED 발행 — <b>실패해도 예외를 올리지 않는다</b> (FRS §6.4).
     *
     * <p>enqueue와 정반대다. enqueue는 발행 실패에 503을 주는데, 그 시점엔 <b>아직 아무것도
     * 확정되지 않아</b> 거절이 성립하기 때문이다. admit은 Lua가 이미 커밋됐다 — 대기열에서
     * 빠졌고 admitToken도 나갔다. 되돌릴 수 없다.
     *
     * <p>여기서 5xx를 주면 Tenant 재시도가 {@code admit-idem} REPLAY로 <b>같은 답만</b> 받고
     * Kafka는 여전히 안 간다. 무한 반복이다. 미반영의 피해는 complete가 {@code status IN (0,1)}로
     * 관대해 이미 흡수한다.
     *
     * <p><b>REPLAY도 발행한다.</b> 컨슈머 UPSERT가 멱등이라 중복은 무해한 반면, 첫 호출에서
     * 발행이 실패했을 때 재시도가 그것을 <b>복구</b>할 수 있는 유일한 경로다.
     *
     * <p>⚠️ <b>REPLAY의 {@code admittedAt}은 첫 admit 시각이 아니라 재시도 시각이다.</b> 멱등
     * payload에 시각이 없어 알 방법이 없다. 첫 발행이 성공했다면 컨슈머 가드
     * ({@code IF(status = 0, ...)})가 이미 status 1이라 이 값을 <b>쓰지 않으므로</b> 무해하고,
     * 첫 발행이 실패한 경우에만 반영되는데 그때는 이 값이 가진 유일한 근거다.
     * 재시도가 늦은 만큼 유효 창(60초)이 뒤로 밀린다.
     *
     * <p>🔴 <b>첫 발행 실패에서 끊는다.</b> 발행은 건별 {@code .get(12초)} 블로킹이라, 브로커가
     * 무응답이면 {@code count=100}짜리 admit 한 건이 <b>최대 20분</b> 동안 요청 스레드를 잡는다.
     * 첫 건이 시한을 다 쓰고 실패했다면 나머지 99건도 같은 브로커를 기다릴 뿐이다.
     *
     * <p>⚠️ <b>건너뛴 분은 자동으로 복구되지 않는다.</b> admit은 발행이 실패해도 200을 주므로
     * Tenant에게 재시도할 이유가 없다 — "복구는 REPLAY"는 Tenant가 <b>마침</b> 같은 requestId로
     * 다시 불렀을 때만 성립하는 <b>가능성</b>이지 경로가 아니다. 그래서 건너뛴 건수와 첫
     * tokenId를 ERROR로 남긴다(그 로그가 유일한 흔적이다).
     *
     * <p>병렬 발행으로는 이 최악을 못 고친다. 메타데이터가 없으면 {@code send()} 자체가
     * 블로킹이라 스레드만 늘고 벽시계는 그대로다.
     */
    private void publishAdmitted(long tenantId, String queueId, AdmitResult result, Instant admittedAt) {
        List<AdmitResult.AdmitRecord> records = result.records();
        for (int i = 0; i < records.size(); i++) {
            AdmitResult.AdmitRecord record = records.get(i);
            if (record.issuedAt() == null) {
                // issuedAt이 없으면 발행할 수 없다. 컨슈머의 멱등 키가 (token_id, issued_at)이라
                // 아무 값이나 넣으면 같은 토큰의 두 번째 행이 생긴다 — 조용히 틀리느니 빼고 남긴다.
                // 도달 경로는 롤링 배포 중의 구버전 멱등 payload뿐이다(AdmitRecord.issuedAt 참조).
                log.error("ADMITTED 발행 생략(issuedAt 미확인) tokenId={} queueId={} identifier={}",
                        record.tokenId(), queueId, record.identifier());
                continue;
            }
            boolean published = publishQuietly(new EnqueueEvent(TokenEventType.ADMITTED.name(),
                    record.tokenId(), queueId, tenantId, record.identifier(), record.seq(),
                    record.issuedAt(), record.admitToken(), admittedAt));
            if (!published) {
                log.error("ADMITTED 발행 중단 queueId={} 건너뜀={}건 첫tokenId={}",
                        queueId, records.size() - i, record.tokenId());
                break;
            }
        }
    }

    /**
     * Verify — admitToken이 지금 유효한지만 답한다 (FRS §6.5). <b>Redis 쓰기 0회, DB 쓰기 0회.</b>
     *
     * @return identifier (Tenant가 어느 사용자인지 알아야 하므로)
     * @throws BusinessException 유효하지 않으면 404 {@code INVALID_ADMIT_TOKEN}
     */
    @Transactional(readOnly = true)
    public String verify(long tenantId, String queueId, String admitToken) {
        findQueueAndVerifyOwner(tenantId, queueId);

        // Redis 히트 = "60초 안에 admit됐다"가 이미 증명된 것(키의 PX가 그 증명이다).
        // 그래서 신원만 읽는다. 여기에 status=1을 걸면 ADMITTED 이벤트를 아직 소비하지 않은
        // 구간의 정상 토큰이 404가 된다 (§6.4 — 200이 보장하지 않는 것).
        Optional<AdmitRef> ref = queueEngine.findAdmitRefByAdmitToken(queueId, admitToken);

        // 값에 identifier가 들어 있으면 **DB를 읽지 않는다**. tokenId만 얻고 신원을 DB에서 찾던
        // 예전 경로는, 컨슈머 백로그로 행이 아직 없는 정상 토큰을 404로 만들었다.
        Optional<String> fromRedis = ref.map(AdmitRef::identifier).filter(id -> !id.isBlank());
        if (fromRedis.isPresent()) {
            return fromRedis.get();
        }

        // 구 포맷(tokenId만)이거나 Redis 미스 → 기존 DB 경로. 후자의 기준 컬럼은
        // issued_at이 아니라 admitted_at이다.
        Token token = ref.map(AdmitRef::tokenId)
                .flatMap(tokenId -> tokenRepository.findByTokenId(queueId, tenantId, tokenId))
                .or(() -> tokenRepository.findAdmittedByAdmitToken(
                        queueId, tenantId, admitToken, ADMIT_TTL_SECONDS))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ADMIT_TOKEN));

        return token.getUserId();
    }

    /**
     * Complete — Tenant가 입장 완료를 통보한다 (FRS §6.6).
     *
     * <p><b>판정 권위는 DB다.</b> Redis {@code admit-by-admit}은 60초면 사라지므로 그걸 기준으로
     * 삼으면 정상 입장이 만료 직후 거절된다 (§80이 구 설계를 폐기한 이유).
     *
     * <p><b>@Transactional인 이유</b>: {@code @Modifying} 네이티브 UPDATE가 트랜잭션을 요구한다.
     * 뒤따르는 조회는 방금 갱신한 행을 읽어야 해서(read-your-write) {@code readOnly}가 아니다.
     * ⚠️ 그 대가로 Redis 정리·Kafka 발행이 트랜잭션 안에 들어온다 — complete는 Tenant 호출이라
     * 저빈도지만, 브로커가 느리면 그만큼 DB 커넥션을 쥔다.
     *
     * @return completedAt (UTC)
     */
    @Transactional
    public LocalDateTime complete(long tenantId, String queueId, String tokenId, String admitToken) {
        findQueueAndVerifyOwner(tenantId, queueId);

        LocalDateTime completedAt = LocalDateTime.now(clock);

        int updated = tokenRepository.markCompleted(
                queueId, tenantId, tokenId, admitToken, completedAt, COMPLETE_VALID_WINDOW_SECONDS);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INVALID_ADMIT_TOKEN);
        }

        // 정리·발행에 identifier·seq·issuedAt이 필요하다. UPDATE가 1행을 갱신했으므로 반드시 있다.
        Token token = tokenRepository.findByTokenId(queueId, tenantId, tokenId)
                .orElseThrow(() -> new IllegalStateException("completed row vanished: " + tokenId));

        queueEngine.cleanupCompleted(queueId, token.getUserId(), tokenId, admitToken, token.getSeq());

        // COMPLETED도 admit과 같은 이유로 조용히 실패한다. DB는 이미 status=2로 확정됐고,
        // 여기서 5xx를 주면 Tenant 재시도가 status IN (0,1)에 걸려 404를 받는다(더 나쁘다).
        // admittedAt은 싣지 않는다 — COMPLETED의 UPSERT는 status만 만지고, admitted_at은
        // ADMITTED가 이미 채운 값이다(§80 가드 표).
        publishQuietly(new EnqueueEvent(TokenEventType.COMPLETED.name(), tokenId, queueId, tenantId,
                token.getUserId(), token.getSeq(), token.getIssuedAt().toInstant(ZoneOffset.UTC),
                admitToken, null));

        return completedAt;
    }

    /**
     * 발행 실패를 삼키고 로그만 남긴다. 호출자 주석에 "왜 삼켜도 되는가"가 있다.
     *
     * @return 성공 여부. 여러 건을 연달아 발행하는 호출자가 <b>첫 실패에서 끊을</b> 근거다
     */
    private boolean publishQuietly(EnqueueEvent event) {
        try {
            eventPublisher.publish(event);
            return true;
        } catch (RuntimeException e) {
            log.error("{} 이벤트 발행 실패 tokenId={} queueId={} — 상태는 이미 확정됐으므로 응답은 200이다",
                    event.eventType(), event.tokenId(), event.queueId(), e);
            return false;
        }
    }

    /**
     * queue 조회 + 소유권 검증 (관리용 QueueService와 동일 패턴).
     */
    private Queue findQueueAndVerifyOwner(Long tenantId, String queueId) {
        Queue queue = queueRepository.findByQueueId(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
        if (!queue.getTenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_OWNED);
        }
        return queue;
    }

    /**
     * 대기 상태 폴링 (FRS §6.3).
     *
     * <p><b>waiting에 없다고 곧장 404를 주지 않는다.</b> admit되면 {@code waiting} ZSet에서 빠지므로
     * ({@code admit.lua}의 ZPOPMIN) 검증만으로는 <b>정상 입장자와 없는 토큰이 구분되지 않는다</b>.
     * 그 둘을 {@code admit-by-token}으로 가른다 — 값이 있으면 입장권을 돌려주고, 없을 때만 404다.
     * 404는 클라이언트에게 재시도가 아니라 <b>종료 신호</b>라 정상 입장자에게 주면 안 된다.
     *
     * <p><b>왜 Lua가 아니라 Java에서 한 번 더 보는가:</b>
     * <ul>
     *   <li>이 조회는 {@code verifyWaiting}이 <b>false일 때만</b> 실행된다 = 대기 중인 폴링
     *       (최대 15만/s)에는 왕복이 늘지 않는다. 늘어나는 쪽은 admit된 사람과 없는 토큰뿐이다.</li>
     *   <li>{@code poll_verify.lua}에 넣으려면 admitToken을 실어 보내야 해서 반환이
     *       {@code Long} → 배열로 바뀐다. 핫패스 이득 0에 파급만 크다.</li>
     *   <li>{@code admit-by-token}은 tokenId가 런타임 값이라 {@code KEYS[]} 선언이 불가능하다.
     *       Lua에서 접두사+ARGV로 만들면 <b>슬롯이 달라도 같은 노드면 조용히 통과</b>하는 구멍이
     *       생기지만(§80 ⑥), 평범한 {@code GET}은 Lettuce가 슬롯으로 정확히 라우팅한다.</li>
     * </ul>
     *
     * <p>⚠️ <b>TTL 만료로 복귀한 사람은 이 분기에 오지 않는다.</b> 복귀 배치가 원래 seq 그대로
     * {@code waiting}에 되돌려 놓으므로 {@code verifyWaiting}이 통과한다 — 정상 대기 응답이다.
     */
    public PollResult poll(String queueId, String tokenId, long seq, boolean keepalive){
        // 존재(seq)만이 아니라 소유권(tokenId)까지 검증한다. seq는 큐별 INCR이라 추측이 자명해서,
        // 존재 판정만 하면 남의 대기 항목에 ka=1로 keepalive를 걸 수 있다.
        // keepalive 갱신도 이 호출 안에서 원자적으로 처리된다(poll_verify.lua).
        String admitToken = null;
        if(!queueEngine.verifyWaiting(queueId, seq, tokenId, keepalive, clock.millis())) {
            // admitted ZSet이 아니라 admit-by-token을 본다. 유효 창은 admitToken의 PX 60초인데
            // admitted는 복귀 배치가 집어갈 때까지 더 오래 남고, 돌려줄 admitToken도 여기에만 있다.
            admitToken = queueEngine.findAdmitTokenByTokenId(queueId, tokenId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));
        }

        // rank·폴링 간격은 계산하지 않는다 — 그건 /status의 lastAdmittedSeq와 pacing으로
        // 클라이언트가 한다(§79). 여기서 계산하면 응답이 사람마다 달라져 분할이 무의미해진다.
        return new PollResult(admitToken != null, admitToken);
    }

    /**
     * 큐 전광판 조회 (FRS §6.3 ①). <b>인증 없음. 30만 명 전원에게 같은 응답.</b>
     *
     * <p>서버가 하는 일은 {@code MGET} 3키 <b>한 왕복</b>이 전부다. rank도, 폴링 간격도 계산하지
     * 않는다 — 개인화를 걷어내야 응답이 전원 동일해지고, 그래야 평상시 폴링 트래픽이
     * {@code EVAL}(write·master 고정)에서 {@code MGET}(read)으로 바뀐다 (§79 Alternative D).
     *
     * <p><b>{@code @Transactional}을 붙이지 않는다.</b> DB를 한 줄도 읽지 않기 때문이다.
     * 여기에 큐 존재 확인용 {@code findQueueAndVerifyOwner}를 넣으면 인증 없는 최대 15만/s가
     * 그대로 MySQL로 간다 — 큐 실재 판정은 {@code MGET}에 실린 {@code seq} 키가 한다(§79 D3).
     *
     * @throws BusinessException 큐에 enqueue 기록이 없으면 404 {@code QUEUE_NOT_FOUND}
     */
    public QueueBoard status(String queueId) {
        return queueEngine.readStatus(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
    }

}
