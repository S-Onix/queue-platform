package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
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
     * ⚠️ <b>이 블록은 상수 설명이다. 상수 자체는 {@code Token.COMPLETE_VALID_WINDOW_SECONDS}로
     * 옮겼고 여기엔 뒤따르는 필드가 없다</b> — 근거를 잃지 않으려고 남겨 둔 기록이다.
     *
     * <p>complete를 받아 주는 유효 창(초). <b>admitToken TTL 60초보다 반드시 길다</b> —
     * Tenant가 이미 유저를 입장시켰는데 통보가 늦은 경우를 덮어야 하기 때문이다
     * (FRS §6.6이 {@code status IN (0,1)}로 관대한 것과 같은 이유).
     * (구 서술의 "TTL이 만료돼 WAITING으로 복귀했는데"는 §36이 복귀를 폐기해 거짓이 됐다.)
     *
     * <p><b>왜 300인가</b>: 이 값은 "Tenant가 얼마나 늦어도 봐줄 것인가"라는 SLA 판단이라
     * 시스템 상수에서 유도되지 않는다. 그래서 <b>이미 있는 숫자</b>에 맞췄다 —
     * {@code RedisQueueEngine.ADMIT_IDEM_TTL_MILLIS}(300_000)가 "Tenant 재시도가 끝났을 시점"으로
     * 잡은 값이고, 그 창이 닫힌 뒤 도착한 complete는 대응할 admit 재시도가 이미 없다.
     * 큐 기본 {@code inactiveTtl}(300초)과도 같아 외울 숫자가 하나로 준다.
     *
     * <p>60초에 대해 5배 여유 = 늦은 complete 통보에 4분을 준다는 뜻이다.
     * 늘리는 건 하위호환, 줄이는 건 파괴적 변경이므로 여기서도 "필요를 채우는 최소"를 잡았다.
     */

    private final QueueRepository queueRepository;
    private final TokenRepository tokenRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;
    private final Clock clock;                        // 시간 주입(테스트 제어)

    /**
     * {@code /status} 응답 캐시 유지 시간(ms). <b>0이면 끈다 — 기본 0.</b>
     *
     * <p><b>왜 기본을 끄는가 (2026-09-02 실측):</b> 재봤더니 <b>Redis가 병목이 아니었다.</b>
     * 캐시 없이도 20,000 rps에서 p99 18.25ms(SLO 50ms의 1/3)이고, 캐시로 MGET을 <b>448배</b>
     * 줄여도 p99는 18.25 → 14.56ms로 20%만 좋아진다 — 지연의 80%가 Redis가 아니라 앱에 있다.
     * 즉 지금 켜서 막히는 것이 없다. §4대로 "안 만들면 무엇이 깨지나"에 답이 없으므로 켜지 않는다.
     * <pre>
     *   유입      캐시OFF p99 / MGET      캐시1s p99 / MGET
     *    2,000     1.47ms /  60,000        1.14ms /     44
     *   20,000    18.25ms / 598,528       14.56ms /  1,337     ← 448배 감소
     * </pre>
     * ⚠️ 20,000 rps는 <b>하니스 한계에 가깝다</b>(k6가 서버와 같은 머신, 200 응답 99.75%).
     * 목표 규모(마스터당 2큐 × 30만 = 약 30,900 rps)는 <b>측정 범위 밖</b>이라, 거기서도
     * Redis가 여유로운지는 <b>미측정</b>이다. 그 구간을 재고 나서 다시 판단한다.
     *
     * <p>🪤 켤 때 알아야 할 것: 스탬피드를 막지 않아 만료 순간 동시 미스가 겹친다.
     * 실측 증폭 <b>44배</b>(20,000 rps에서 이상적 30회 대비 1,337회). 그래도 448배 이득이라
     * 락을 걸지 않았다 — 락이 곧 새 직렬화 지점이 된다.
     *
     * <p><b>왜 캐시가 가능한가:</b> 이 응답은 <b>30만 명 전원에게 동일</b>하다. §79가 일부러
     * 개인화를 걷어냈고(rank는 클라이언트가 자기 seq와 lastAdmittedSeq로 계산한다), 그래서
     * 같은 큐의 모든 폴링이 같은 바이트를 받는다.
     *
     * <p><b>무엇을 버는가:</b> pacing 구간표로 산술하면 30만 명 큐 하나가 초당 약 15,400건을
     * 만든다. 그 전량이 지금 Redis {@code MGET}으로 간다. 1초 캐시면 <b>인스턴스당 초당 1회</b>가
     * 된다 — 큐 하나에 대해 15,400 → (인스턴스 수).
     *
     * <p>⚠️ <b>이 값은 아래 사슬에 들어간다.</b> 캐시가 오래 살수록 입장 인지가 늦다:
     * <pre>   pacing 최대 간격 + 캐시 TTL &lt; admitToken TTL(60초)   </pre>
     * 지금은 20 + 1 &lt; 60이라 여유가 크다. pacing 꼬리를 늘릴 때 이 항도 같이 세라.
     */
    private final long statusCacheMillis;

    /**
     * queueId → (응답, 만료 시각). 🔴 <b>존재하는 큐만 넣는다.</b>
     *
     * <p>미존재 큐를 캐시하면 인증 없는 폴링(§79 permitAll)에 임의 queueId를 섞는 것만으로
     * 이 맵이 무한히 자란다 — 폴링 리미터 버킷이 같은 이유로 겪는 문제다. 404는 캐시하지
     * 않으므로 엔트리 수가 <b>실재하는 큐 수</b>로 묶인다.
     *
     * <p>🪤 만료된 엔트리를 청소하는 주체가 없다. 삭제된 큐의 엔트리는 프로세스 수명 동안 남는데,
     * 큐 수가 수천 단위라 무해하다고 판단했다. 큐가 수십만이 되면 크기 상한이 필요하다.
     */
    private final Map<String, CachedBoard> statusCache = new ConcurrentHashMap<>();

    private record CachedBoard(QueueBoard board, long expiresAtMillis) {}

    public QueueEngineService(QueueRepository queueRepository, TokenRepository tokenRepository,
                              QueueEngine queueEngine, EnqueueEventPublisher eventPublisher,
                              Clock clock,
                              @Value("${queue.status.cache-ms:0}") long statusCacheMillis) {
        this.queueRepository = queueRepository;
        this.tokenRepository = tokenRepository;
        this.queueEngine = queueEngine;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.statusCacheMillis = statusCacheMillis;
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
                    record.issuedAt(), record.admitToken(), admittedAt, null));
            if (!published) {
                log.error("ADMITTED 발행 중단 queueId={} 건너뜀={}건 첫tokenId={}",
                        queueId, records.size() - i, record.tokenId());
                break;
            }
        }
    }

    /**
     * Verify — admitToken이 지금 유효한지만 답한다 (FRS §6.5). <b>Redis 쓰기 0회, DB 쓰기 0회.</b> 다만 <b>부수효과가 0은 아니다</b> — 응답과 함께 {@code COMPLETED}를 발행한다(그 근거는 아래).
     *
     * @return identifier (Tenant가 어느 사용자인지 알아야 하므로)
     * @throws BusinessException 유효하지 않으면 404 {@code INVALID_ADMIT_TOKEN}
     */
    // 🔴 **트랜잭션을 걸지 않는다.** verify는 이제 Kafka를 동기로 기다리는데(send-timeout 12초),
    //    트랜잭션 안이면 LazyConnectionDataSourceProxy가 커넥션을 **트랜잭션이 끝날 때까지 쥔다**.
    //    브로커가 느려지면 Replica 풀이 verify에 12초씩 묶이고, verify는 게이트 개방 순간
    //    입장자 수만큼 몰리는 엔드포인트라 그게 곧 자해가 된다.
    //    Redis 히트 경로는 DB를 아예 안 읽는다.
    //
    //    🪤 **폴백 경로의 단건 조회는 master로 간다.** 구 주석은 "Spring Data 리포지토리가 자체
    //       readOnly 트랜잭션을 갖고 있어 Replica 라우팅도 그대로"라고 적었는데 **거짓이다**.
    //       2026-08-27 라우팅 로그 실측: 트랜잭션 없이 부른 파생 쿼리(findByTokenId 등)는
    //       readOnly 트랜잭션이 **열리지 않아** isCurrentTransactionReadOnly()가 false다 → master.
    //       같은 findByQueueId도 @Transactional(readOnly=true) 안에서 부르면 replica로 간다
    //       (QueueService.getQueue로 대조 확인). **갈리는 것은 메서드가 아니라 트랜잭션이다.**
    //       → 트랜잭션을 안 거는 이 판단 자체는 유지한다. 다만 대가가 "Replica 풀 점유"가
    //         아니라 **master 풀 점유**라는 것이 정확한 서술이다.
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
            // 🔑 **verify 응답을 주는 시점이 완료다.** Platform의 책임은 답을 돌려주는 데까지이고,
            //    그 뒤 Tenant 안에서 좌석 배정·세션 생성이 어떻게 되는지는 관측할 수도 책임질 수도
            //    없다 (CLAUDE.md 원칙 1 — Platform은 순서만, Tenant가 입장 제어).
            //    이 전이가 없으면 complete를 안 부르는 Tenant의 행이 status=1로 영원히 남는다.
            //
            //    🔴 **DB를 직접 쓰지 않고 이벤트만 발행한다.**
            //
            //    ⚠️ 구 주석은 근거를 "이 메서드는 @Transactional(readOnly = true)라 Replica로
            //       라우팅되고 UPDATE가 실패한다"로 적었는데 **그 전제가 거짓이다** — 이 메서드에는
            //       트랜잭션 어노테이션이 아예 없다(위 주석 참조. Kafka send-timeout 12초를
            //       커넥션 쥔 채 기다리지 않으려고 일부러 뺐다).
            //
            //    진짜 근거는 둘이다: ① 쓰기 트랜잭션을 열면 Redis 히트로 끝나는 정상 경로까지
            //    Master 커넥션을 잡아 "verify는 DB를 안 읽는다"는 설계(§6.4)가 되돌아간다.
            //    ② 여기서 UPDATE하면 Kafka 소비 경로와 두 갈래로 갈려 순서 보장이 사라진다.
            //    🔴 **주석대로 readOnly를 되붙이지 마라** — 그게 F-3(verify가 Kafka 12초 동안
            //       커넥션 점유) 사고의 재발 경로다.
            //
            //    이벤트 경로가 오히려 안전하다 — 파티션 키가 tokenId라
            //    ENQUEUED → ADMITTED → COMPLETED 순서가 보장되므로, 컨슈머 백로그 구간이어도
            //    ADMITTED가 먼저 적용된 뒤에 이 전이가 얹힌다.
            publishCompletedOnVerify(tenantId, queueId, admitToken, ref.get());
            return fromRedis.get();
        }

        // 구 포맷(tokenId만)이거나 Redis 미스 → 기존 DB 경로. 후자의 기준 컬럼은
        // issued_at이 아니라 admitted_at이다.
        Token token = ref.map(AdmitRef::tokenId)
                .flatMap(tokenId -> tokenRepository.findByTokenId(queueId, tenantId, tokenId))
                .or(() -> tokenRepository.findAdmittedByAdmitToken(
                        queueId, tenantId, admitToken, ADMIT_TTL_SECONDS))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ADMIT_TOKEN));

        // 폴백 경로도 완료 판정은 같다. 여기는 이미 DB를 읽은 뒤라 seq·issuedAt이 손에 있다.
        publishQuietly(new EnqueueEvent(
                TokenEventType.COMPLETED.name(), token.getTokenId(), queueId, tenantId,
                token.getUserId(), token.getSeq(), token.getIssuedAt().toInstant(ZoneOffset.UTC),
                admitToken, null, null));

        return token.getUserId();
    }

    /**
     * verify가 답을 돌려주는 시점에 {@code COMPLETED}를 발행한다.
     *
     * <p>구 포맷 값(롤링 배포 중)이면 {@code seq}·{@code issuedAt}이 없어 이벤트를 만들 수 없다.
     * 그때는 <b>건너뛴다</b> — verify의 계약(identifier 반환)은 지켜야 하고, 그 구간의 누락은
     * Tenant가 {@code complete}를 부르거나 reconciliation이 메운다.
     */
    private void publishCompletedOnVerify(long tenantId, String queueId, String admitToken, AdmitRef ref) {
        if (!ref.complete()) {
            log.warn("구 포맷 admit-by-admit — 완료 발행 생략 queueId={} tokenId={}", queueId, ref.tokenId());
            return;
        }
        publishQuietly(new EnqueueEvent(
                TokenEventType.COMPLETED.name(), ref.tokenId(), queueId, tenantId,
                ref.identifier(), ref.seq(), ref.issuedAt(), admitToken, null, null));
    }

    /**
     * Complete — Tenant가 입장 완료를 통보한다 (FRS §6.6).
     *
     * <p><b>판정 권위는 DB가 먼저, 그 다음이 Redis다.</b> {@code markCompleted}가 1행이면 거기서
     * 끝난다 — 원장이 동기로 확정되므로 발행이 실패해도 상태가 남는다. <b>0행일 때만</b> Redis
     * {@code admit-by-admit}으로 폴백하는데, 그 0행에는 두 가지가 섞여 있다: ① 자격 없음,
     * ② <b>컨슈머가 ADMITTED를 아직 적재하지 않음</b>. ②까지 404로 돌려주면 <b>정상 입장자가
     * 거절된다</b> — §80이 "발생률은 통합테스트에서 관측한다"고 남긴 그 창이다.
     *
     * <p>§80이 폐기한 것은 <b>"Redis 미스면 404"</b>라는 구 설계이지(FRS §6.6) DB 폴백이 아니다.
     * Redis 히트 창(PX 60초)은 DB 창(300초)의 부분집합이라, 폴백이 통과시키는 요청은 적재만
     * 끝났다면 UPDATE도 통과시켰을 것들이다.
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
                queueId, tenantId, tokenId, admitToken, completedAt, Token.COMPLETE_VALID_WINDOW_SECONDS);
        if (updated == 0) {
            // 🔴 **이미 COMPLETED면 성공이다.** verify가 완료를 확정하게 되면서
            //    verify → complete를 둘 다 부르는 정상 Tenant가 여기 도달한다.
            //    admitToken이 일치하는 완료 행이면 그때의 completedAt을 그대로 돌려준다
            //    — 재시도에도 같은 답이 나온다.
            Optional<LocalDateTime> already =
                    tokenRepository.findCompletedAt(queueId, tenantId, tokenId, admitToken);
            if (already.isPresent()) {
                return already.get();
            }

            // 🔑 **여기까지 왔다 = DB가 이 토큰을 아직 모른다.**
            //
            // markCompleted의 술어가 요구하는 admit_token·admitted_at은 ADMITTED 이벤트를
            // 컨슈머가 적재해야만 채워진다. 즉 **랙 구간에서는 정상 입장자도 0행**이다.
            // verify가 같은 창에서 멀쩡한 이유는 Redis를 먼저 보기 때문이다(§6.5).
            //
            // 🔑 **정당화는 실측이 아니라 불변식이다.** admit-by-admit의 PX는 60초이고
            //    complete의 DB 창은 300초다 — **Redis 히트 창 ⊂ DB 창**. 그러므로 이 폴백이
            //    통과시키는 요청은 예외 없이 **적재만 끝났다면 위 UPDATE도 통과시켰을 것들**이고,
            //    자격이 넓어지지 않는다. 부하 수치가 없어도 이 문장은 참이다.
            //    (두 상수가 갈리면 이 불변식이 깨진다 — Token.COMPLETE_VALID_WINDOW_SECONDS와
            //     admit TTL을 같이 보고 고쳐라.) §80이 기각한 것은 "Redis 미스면 404"라는
            // 구 설계이지(FRS §6.6) DB 폴백이 아니다.
            //
            // ⚠️ **순서를 뒤집지 마라.** DB를 먼저 치는 덕에 랙이 없는 정상 경로는 변경 전과
            //    완전히 같다 — status=2가 동기로 확정되고, 재시도는 findCompletedAt이 같은 값을
            //    돌려주며, 발행이 실패해도 원장은 이미 확정돼 있다. Redis를 먼저 보게 만들면
            //    그 안전망이 사라져, publishQuietly가 발행을 삼켰을 때 행이 status=1로 남고
            //    ReconcileJob이 **완료된 토큰을 EXPIRED로 확정**한다(Redis 키는 이미 지워진 뒤라
            //    복구 경로가 없다).
            AdmitRef ref = queueEngine.findAdmitRefByAdmitToken(queueId, admitToken)
                    .filter(AdmitRef::complete)
                    .filter(r -> tokenId.equals(r.tokenId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ADMIT_TOKEN));

            // 🔴 **폴백은 조용히 일어나면 안 된다.**
            // 이 경로가 도는 동안 원장(tokens)에는 이 완료가 없다. 컨슈머가 잠깐 밀린 것이면
            // 곧 따라잡지만, **적재가 아예 멈춰도 여기는 계속 200을 준다** — Tenant는 정상이라
            // 믿고 원장은 비어 있는 상태가 된다(과금 근거도 사라진다).
            // 전례: 컨슈머가 죽은 판에서 이 경로가 계속 200을 내는 동안 tokens 테이블은
            // 비어 있었고, 그때 이 로그가 없어 **아무도 몰랐다.**
            // (그 판의 산출물은 소실됐다 — 건수는 재현 미검증이라 적지 않는다.)
            // WARN인 이유: 정상 운영에서도 랙이 있으면 나오지만, 지속되면 그 자체가 사고다.
            log.warn("complete가 Redis 폴백으로 처리됐다 — 컨슈머 적재가 밀려 있다. "
                    + "tokenId={} queueId={} seq={}", tokenId, queueId, ref.seq());

            queueEngine.cleanupCompleted(queueId, ref.identifier(), tokenId, admitToken, ref.seq());
            // 이벤트에 필요한 값이 AdmitRef 안에 전부 있다. DB를 다시 읽지 않는다.
            publishQuietly(new EnqueueEvent(TokenEventType.COMPLETED.name(), tokenId, queueId, tenantId,
                    ref.identifier(), ref.seq(), ref.issuedAt(), admitToken, null, null));
            return completedAt;
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
                admitToken, null, null));

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
     * <p>🔴 <b>admitToken TTL이 만료된 사람은 정확히 이 분기로 와서 404를 받는다. 그게 의도다.</b>
     * (구 주석은 "복귀 배치가 waiting에 되돌려 놓으므로 이 분기에 오지 않는다"였다 — <b>§36이
     * 복귀를 폐기</b>해 거짓이 됐다. {@code admit_expire.lua}에 {@code ZADD waiting}이 없다.)
     *
     * <p>회수 배치가 {@code admitted}에서 빼고 {@code tokens} Hash를 {@code HDEL}하므로
     * {@code verifyWaiting}도 {@code findAdmitTokenByTokenId}도 실패한다 → 404가 <b>종료 신호</b>다.
     * 재접속하면 재-enqueue라 맨 뒤다. <b>이 404를 버그로 보고 고치면 §36 계약이 깨진다.</b>
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
        if (statusCacheMillis <= 0) {
            return loadStatus(queueId);
        }
        long now = clock.millis();
        CachedBoard hit = statusCache.get(queueId);
        if (hit != null && hit.expiresAtMillis() > now) {
            return hit.board();
        }
        // 미스가 겹치면 여럿이 같이 Redis를 친다(스탬피드). 막지 않는다 —
        // 겹치는 창이 캐시 TTL 하나이고, 그 사이 중복은 인스턴스당 몇 건 수준이다.
        // 락을 걸면 그 락이 곧 새 직렬화 지점이 되어 폴링 전체가 그 뒤에 선다.
        QueueBoard board = loadStatus(queueId);
        statusCache.put(queueId, new CachedBoard(board, now + statusCacheMillis));
        return board;
    }

    /** 캐시를 거치지 않는 원본 조회. 404는 캐시하지 않으므로 여기서 던진다. */
    private QueueBoard loadStatus(String queueId) {
        return queueEngine.readStatus(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
    }

}
