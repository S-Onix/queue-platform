package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class QueueEngineService {

    /**
     * admitToken 유효 시간(초). {@code RedisQueueEngine.ADMIT_TTL_MILLIS}(60_000)와 같은 값이며,
     * verify의 DB fallback이 {@code admitted_at} 신선도를 재는 창이다 (FRS §6.4·§6.5).
     */
    private static final int ADMIT_TTL_SECONDS = 60;


    private final QueueRepository queueRepository;
    private final TokenRepository tokenRepository;
    private final QueueEngine queueEngine;
    private final EnqueueEventPublisher eventPublisher;
    private final Clock clock;                        // 시간 주입(테스트 제어)
    private final MeterRegistry meterRegistry;

    /**
     * 이유: {@code queue_admission_wait_seconds} 의 버킷 경계(MONITORING_DESIGN 4-3). <b>여기 한 곳에만 있다.</b>
     * 문제: 상한이 3600초였을 때 AWS 8차(500만)에서 <b>p95 가 1시간에 박혔다 — 초과 27.1%</b>.
     * 원인: 넘긴 표본이 전부 {@code +Inf} 로 몰려 <b>기록은 되는데 읽을 수 없는 값</b>이 된다.
     * 해결: 위로 7200·14400, 아래로 1·3초 — <b>p5·p30 을 보려면 저구간 해상도가 필요하다</b>.
     * 🪤 경계는 SLO 판단이라 흩어 놓으면 알람과 어긋난다(경고 p95 1분·위험 5분이 60·300 에 걸려 있다).
     */
    private static final Duration[] ADMISSION_WAIT_SLO = {
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(600),
            Duration.ofSeconds(1800), Duration.ofSeconds(3600),
            Duration.ofSeconds(7200), Duration.ofSeconds(14400)
    };

    /**
     * 이유: 구간별 소요 버킷. 경계가 없으면 {@code _bucket} 미발행이라 p95 패널이 빈다.
     * 🪤 상한은 <b>가능한 최대</b>에 맞춘다 — kafka 구간은 {@code send-timeout} 12초까지 갈 수 있어
     *    1초에서 끊으면 부하 중에 p95 가 1초에 포화한다(바로 위 ADMISSION_WAIT_SLO 가 3600초에서 겪은 것과 같은 결함).
     */
    private static final Duration[] STAGE_SLO = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(20),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(12)};

    public QueueEngineService(QueueRepository queueRepository, TokenRepository tokenRepository,
                              QueueEngine queueEngine, EnqueueEventPublisher eventPublisher,
                              Clock clock, MeterRegistry meterRegistry) {
        this.queueRepository = queueRepository;
        this.tokenRepository = tokenRepository;
        this.queueEngine = queueEngine;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
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

        // 🔑 PAUSED는 **입구만** 잠근다. 이미 줄에 선 사람의 재-enqueue(=새로고침)는 통과시킨다 —
        //    막으면 화면을 새로고침한 것만으로 자리를 잃는다(2026-09-15 실증).
        //    🪤 신규/기존 판정은 원래 enqueue_bulk.lua의 HSETNX가 한다. 그런데 이 가드가 Lua보다
        //       **앞**에 있어 물어보기도 전에 막고 있었다. 그래서 여기서 한 번 물어본다.
        //    🔑 ACTIVE는 첫 조건에서 끝나므로 **핫패스에 왕복이 붙지 않는다**.
        if (!queue.isEnqueueable()
                && !(queue.allowsRejoin() && queueEngine.hasToken(queueId, identifier))) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_ACTIVE);
        }

        EnqueueResult result = queueEngine.enqueue(queueId, identifier);

        if (result.isFull()) {
            throw new BusinessException(ErrorCode.QUEUE_FULL);
        }

        if (result.isOk()) {
            // 계기판: enqueue 응답의 최대 항목이 이 동기 발행이다(손실측 약 18ms). 코드에 심어 회귀를 잡는다.
            long kafkaStart = System.nanoTime();
            eventPublisher.publish(EnqueueEvent.of(tenantId, queueId, result));
            io.micrometer.core.instrument.Timer.builder("queue.stage.duration").tag("stage", "kafka")
                    .serviceLevelObjectives(STAGE_SLO)
                    .register(meterRegistry)
                    .record(System.nanoTime() - kafkaStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        return result;
    }

    /**
     * 이유: 대기열 앞에서 count 명을 꺼내 admitToken 을 발급한다(FRS §6.4).
     * 원인: 순번은 Redis 가 먼저 쓰고 DB 엔 Kafka 로 나중에 들어간다. 중간에 DB 를 확인하면 아직 적재 전인
     *       정상 대기자를 없는 사람으로 보고 지우고, 복구 근거까지 사라진다(§71 D11 · §80).
     * 해결: {@code admit.lua} 하나로 전 구간을 원자로 처리한다 — <b>중간에 DB 를 보지 않는다</b>.
     * 🪤 {@code @Transactional} 금지 — Redis EVAL 과 Kafka 발행이 통째로 커넥션을 잡는다.
     *
     * @author sonix
     */
    public AdmitResult admit(long tenantId, String queueId, int count, String requestId) {
        Queue queue = findQueueAndVerifyOwner(tenantId, queueId);

        // 🔴 삭제된 큐에서 **새 입장권을 더 내주지 않는다.** 소프트 삭제라 행이 남아 있어
        //    존재·소유 확인만으로는 통과했고, 지운 큐에서 admit이 계속 200이었다.
        //    🔑 verify·complete는 **막지 않는다** — 삭제 시점에 이미 입장권을 들고 좌석으로
        //       가던 사람까지 자르면 "돈은 받고 입장은 못 시킨" 사용자가 생긴다. 그쪽은
        //       admitToken TTL 60초와 완료 창 300초로 이미 유계다.
        if (queue.isDeleted()) {
            throw new BusinessException(ErrorCode.QUEUE_NOT_FOUND);
        }

        long now = clock.millis();
        AdmitResult result = queueEngine.admit(queueId, requestId, count, now);

        Instant admittedAt = Instant.ofEpochMilli(now);
        recordAdmissionWait(queueId, result, admittedAt);
        int skipped = publishAdmitted(tenantId, queueId, result, admittedAt);
        recordAdmitRequest(queueId, result, skipped);

        return result;
    }

    /**
     * 이유: 대기 시간 분포 {@code queue_admission_wait_seconds{queue_id}} 기록(MONITORING_DESIGN 4-3).
     * 해결: {@code issuedAt} 과 admit 시각의 차. <b>추가 조회 0</b> — 둘 다 이미 손에 있다.
     * 🔴 <b>REPLAY 는 기록하지 않는다</b> — 쓸 수 있는 시각이 재시도 시각뿐이라 p95 가 부푼다.
     *
     * @author sonix
     */
    private void recordAdmissionWait(String queueId, AdmitResult result, Instant admittedAt) {
        if (result.replay()) {
            return;
        }
        Timer timer = null;
        for (AdmitResult.AdmitRecord record : result.records()) {
            if (record.issuedAt() == null) {
                continue;   // 구 포맷 — 발행과 같은 이유로 뺀다(publishAdmitted 참조)
            }
            long waitMillis = admittedAt.toEpochMilli() - record.issuedAt().toEpochMilli();
            if (waitMillis < 0) {
                // 이유: **음수를 0으로 눕히지 않는다.** 두 시각 모두 앱 시계라 N대 스큐가 들어온다(-398초 실측).
                // 문제: clamp 하면 스큐 신호가 사라지고, 그냥 record 하면 Timer 가 음수를 조용히 버린다.
                // 해결: 빼되 Timer 로 드러낸다 — 크기까지 남겨야 "경계 잡음"과 "시계 고장"이 갈린다.
                // 🪤 미터가 **값 1로 태어나** increase() 델타가 0이다 — 보정은 alerts/app.yml 이 한다.
                Timer.builder("queue.admission.clock.skew")
                        .description("admit 시각이 enqueue 시각보다 앞선 크기 (API 서버 간 시계 스큐)")
                        .tag("queue_id", queueId)
                        .register(meterRegistry)
                        .record(-waitMillis, TimeUnit.MILLISECONDS);
                continue;
            }
            if (timer == null) {
                timer = Timer.builder("queue.admission.wait")
                        .description("enqueue부터 admit까지 걸린 시간")
                        .tag("queue_id", queueId)
                        .serviceLevelObjectives(ADMISSION_WAIT_SLO)
                        .register(meterRegistry);
            }
            timer.record(waitMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 이유: admit 요청과 발급 토큰 수를 센다 — {@code queue_admit_requests_total{queue_id,result}}(§80 U9).
     * 🔑 라벨 철자는 <b>{@code queue_id}</b> 다 — 갈리면 {@code and on(queue_id)} 조인이 깨진다.
     * 🔴 {@code result=error} 는 <b>발행 실패</b>다 — admit 은 항상 200 이라 HTTP 로는 안 보인다.
     * 🔴 <b>REPLAY 판정이 error 보다 먼저다</b> — 뒤집으면 멀쩡한 행에 critical 알람이 뜬다.
     *
     * @author sonix
     */
    private void recordAdmitRequest(String queueId, AdmitResult result, int skipped) {
        String outcome = result.replay() ? "replay"
                : skipped > 0 ? "error"
                : result.records().isEmpty() ? "empty"
                : "ok";
        Counter.builder("queue.admit.requests")
                .description("admit 요청 건수")
                .tag("queue_id", queueId)
                .tag("result", outcome)
                .register(meterRegistry)
                .increment();

        if (!result.replay() && !result.records().isEmpty()) {
            Counter.builder("queue.admit.tokens.issued")
                    .description("admit이 발급한 admitToken 수")
                    .tag("queue_id", queueId)
                    .register(meterRegistry)
                    .increment(result.records().size());
        }
    }

    /**
     * 이유: ADMITTED 발행 — 실패해도 예외를 올리지 않는다. Lua 가 이미 커밋돼 5xx 를 주면 Tenant 재시도가
     *       같은 requestId 의 저장된 결과(REPLAY)만 끝없이 받는다(FRS §6.4).
     * 문제: 건별 ack 대기는 지연이 건수에 선형이었다(AWS 12차 300건 p50 1.797초).
     * 해결: 전량 보낸 뒤 한 번에 기다리고 첫 실패에서 끊지 않는다. REPLAY 도 다시 발행한다(적재가 멱등 — 같은 requestId 재시도가 유일한 복구 경로). §96-1
     * ⚠️ 재시도가 없으면 실패분은 Redis 입장권(60초)만 있고 DB 엔 없어 60초 뒤 complete 가 404 다. 흔적은 ERROR 로그와 result=error.
     *
     * @author sonix
     * @return 원장을 잃은 건수 (발행 실패 + issuedAt 미확인으로 발행조차 못 한 건)
     */
    private int publishAdmitted(long tenantId, String queueId, AdmitResult result, Instant admittedAt) {
        List<EnqueueEvent> events = new ArrayList<>(result.records().size());
        int skipped = 0;
        for (AdmitResult.AdmitRecord record : result.records()) {
            if (record.issuedAt() == null) {
                skipped++;
                // issuedAt이 없으면 발행할 수 없다. 컨슈머의 멱등 키가 (token_id, issued_at)이라
                // 아무 값이나 넣으면 같은 토큰의 두 번째 행이 생긴다 — 조용히 틀리느니 빼고 남긴다.
                // 도달 경로는 롤링 배포 중의 구버전 멱등 payload뿐이다(AdmitRecord.issuedAt 참조).
                log.error("ADMITTED 발행 생략(issuedAt 미확인) tokenId={} queueId={} identifier={}",
                        record.tokenId(), queueId, record.identifier());
                continue;
            }
            events.add(new EnqueueEvent(TokenEventType.ADMITTED.name(),
                    record.tokenId(), queueId, tenantId, record.identifier(), record.seq(),
                    record.issuedAt(), record.admitToken(), admittedAt, null));
        }
        if (events.isEmpty()) {
            return skipped;
        }
        // 🔴 **예외를 여기서 잡는다** — 올리면 admit 이 503 이 되고 FRS §6.4 가 깨진다.
        //    Lua 가 이미 커밋돼 되돌릴 수 없으므로 5xx 는 재시도를 REPLAY 무한 반복으로 만든다.
        //    🪤 어댑터는 자기 안에서 다 잡지만 **포트 계약이 그걸 보장하지 않는다**(기본 구현·미래의
        //       두 번째 어댑터). 예전 건별 경로는 publishQuietly 가 감싸 줬는데 그 보호가 사라졌었다.
        int failed;
        try {
            failed = eventPublisher.publishAll(events);
        } catch (RuntimeException e) {
            failed = events.size();   // 어디까지 갔는지 모른다 — 전부 잃은 것으로 센다(보수적)
            log.error("ADMITTED 발행이 예외로 중단됐다 queueId={} 시도={}건", queueId, events.size(), e);
        }
        if (failed > 0) {
            log.error("ADMITTED 발행 실패 queueId={} 실패={}건 / 시도={}건", queueId, failed, events.size());
        }
        return skipped + failed;
    }

    /**
     * 이유: Verify — admitToken 이 지금 유효한지 답하고, <b>그 응답이 곧 완료다</b>(FRS §6.5 · PR #48).
     * 해결: DB 쓰기 0회 — COMPLETED 를 발행하고, Redis 는 입장권→토큰 역조회 키(admit-by-admit)만 남긴다(60초 안 재-verify 통과, §92).
     * 🔴 @Transactional 을 걸지 마라 — Kafka 동기 발행(최대 12초) 동안 커넥션을 쥔다. 폴백 조회는 master 로 간다(§4-3). §96-2
     *
     * @author sonix
     * @return identifier (Tenant가 어느 사용자인지 알아야 하므로)
     * @throws BusinessException 유효하지 않으면 404 {@code INVALID_ADMIT_TOKEN}
     */
    public String verify(long tenantId, String queueId, String admitToken) {
        findQueueAndVerifyOwner(tenantId, queueId);

        // Redis 히트 = "60초 안에 admit됐다"가 이미 증명된 것(키의 PX가 그 증명이다).
        // 그래서 신원만 읽는다. 여기에 status=1을 걸면 ADMITTED 이벤트를 아직 소비하지 않은
        // 구간의 정상 토큰이 404가 된다 (§6.4 — 200이 보장하지 않는 것).
        Optional<AdmitRef> ref = queueEngine.findAdmitRefByAdmitToken(queueId, admitToken);

        // 값에 identifier가 들어 있으면 **DB를 읽지 않는다**. tokenId만 얻고 신원을 DB에서 찾던
        // 예전 경로는, 컨슈머 백로그로 행이 아직 없는 정상 토큰을 404로 만들었다.
        Optional<String> fromRedis = ref.map(AdmitRef::identifier).filter(id -> !id.isBlank());
        countPath("queue.verify.result", "result", fromRedis.isPresent() ? "redis" : "db_fallback");
        if (fromRedis.isPresent()) {
            // 이유: **verify 응답을 주는 시점이 완료다.** 전이가 없으면 complete 를 안 부르는
            //       Tenant 의 행이 status=1 로 영원히 남는다(원칙 1 — Platform 은 순서만).
            // 🔴 **DB 를 직접 쓰지 않고 이벤트만 발행한다.** ①쓰기 트랜잭션은 정상 경로까지
            //    Master 커넥션을 잡는다 ②UPDATE 하면 Kafka 경로와 갈려 순서 보장이 사라진다.
            // 🔴 **@Transactional 을 붙이지 마라** — verify 가 Kafka 12초 동안 커넥션을 쥐는 재발 경로다.

            // 🔴 **Redis 는 정리하되 admit-by-admit 은 남긴다(§92).** 정리가 complete 에만 있던 때는
            //    verify 만 부르는 Tenant 의 완료자가 70초 동안 줄 없이 재입장했고 과금이 1 vs 2 로
            //    갈렸다(2026-09-11 실측). admit-by-admit 은 verify 재시도와 complete 폴백의 유일한 근거다.
            AdmitRef hit = ref.get();
            if (hit.complete()) {
                queueEngine.cleanupVerified(queueId, hit.identifier(), hit.tokenId(), hit.seq());
            }
            publishCompletedOnVerify(tenantId, queueId, admitToken, hit);
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
     * 이유: Complete — Tenant 가 입장 완료를 통보한다(FRS §6.6). 판정은 DB 먼저, 0행이면 Redis 폴백이다(§93).
     * 문제: 0행에는 ①자격 없음 ②컨슈머가 ADMITTED 를 아직 적재 안 함이 섞인다 — ②를 404 로 돌리면 정상 입장자가 거절된다.
     * 🔴 폴백은 입장 후 60초까지만 덮는다. 적재가 60초 넘게(L초) 밀리면 입장 후 60~L초의 complete 는 404(TK002)이고, 적재가 따라잡으면 재시도로 200이다.
     * 🔴 @Transactional 을 다시 붙이지 마라 — Redis 왕복과 폴백의 Kafka 동기 발행(12초) 동안 커넥션을 쥔다.
     *    DB 작업은 markCompleted 한 문장이고 트랜잭션은 어댑터가 갖는다. 근거·실측 §96-3 · §95
     *
     * @author sonix
     */
    public LocalDateTime complete(long tenantId, String queueId, String tokenId, String admitToken) {
        findQueueAndVerifyOwner(tenantId, queueId);

        // 🔴 DB 정밀도(DATETIME(3))로 자른다. 안 자르면 첫 응답은 나노초, 재시도는 DB 에서 읽은 밀리초라
        //    "재시도해도 같은 completedAt"(API.md complete) 계약이 깨진다 — 실 DB 테스트에서 드러났다
        LocalDateTime completedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);

        int updated = tokenRepository.markCompleted(
                queueId, tenantId, tokenId, admitToken, completedAt, Token.COMPLETE_VALID_WINDOW_SECONDS);
        countPath("queue.complete.path", "path", updated == 1 ? "update" : "zero_row");
        if (updated == 0) {
            // 🔴 **이미 COMPLETED면 성공이다.** verify가 완료를 확정하게 되면서
            //    verify → complete를 둘 다 부르는 정상 Tenant가 여기 도달한다.
            //    admitToken이 일치하는 완료 행이면 그때의 completedAt을 그대로 돌려준다
            //    — 재시도에도 같은 답이 나온다.
            Optional<LocalDateTime> already =
                    tokenRepository.findCompletedAt(queueId, tenantId, tokenId, admitToken);
            if (already.isPresent()) {
                countPath("queue.complete.path", "path", "already");
                return already.get();
            }

            // 여기까지 왔다 = DB가 이 토큰을 아직 모른다(컨슈머 랙). 그래서 Redis로 폴백한다.
            // 자격이 넓어지지 않는 근거는 불변식이다 — admit-by-admit PX 60초 ⊂ DB 창 300초.
            // 🔑 시계는 전제가 아니다: 양변을 MySQL이 찍으므로 앱 시계 스큐에 면역이다(§90).
            // ⚠️ 순서를 뒤집지 마라 — Redis를 먼저 보면 ReconcileJob이 완료 토큰을 EXPIRED로 확정한다.
            // 근거·이력·실측 전문은 doc/DECISIONS.md §93.
            AdmitRef ref = queueEngine.findAdmitRefByAdmitToken(queueId, admitToken)
                    .filter(AdmitRef::complete)
                    .filter(r -> tokenId.equals(r.tokenId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ADMIT_TOKEN));

            // 🔴 **폴백은 조용히 일어나면 안 된다.** 적재가 아예 멈춰도 여기는 계속 200을 준다 —
            //    Tenant는 정상이라 믿고 원장(tokens)은 비어 간다(과금 근거도 사라진다).
            //    WARN인 이유: 랙이 있으면 정상 운영에서도 나오지만, 지속되면 그 자체가 사고다.
            log.warn("complete가 Redis 폴백으로 처리됐다 — 컨슈머 적재가 밀려 있다. "
                    + "tokenId={} queueId={} seq={}", tokenId, queueId, ref.seq());

            countPath("queue.complete.path", "path", "redis_fallback");
            queueEngine.cleanupCompleted(queueId, ref.identifier(), tokenId, admitToken, ref.seq());
            // 이벤트에 필요한 값이 AdmitRef 안에 전부 있다. DB를 다시 읽지 않는다.
            publishQuietly(new EnqueueEvent(TokenEventType.COMPLETED.name(), tokenId, queueId, tenantId,
                    ref.identifier(), ref.seq(), ref.issuedAt(), admitToken, null, null));
            return completedAt;
        }

        // 정리에 identifier·seq가 필요하다. UPDATE가 1행을 갱신했으므로 반드시 있다.
        Token token = tokenRepository.findByTokenId(queueId, tenantId, tokenId)
                .orElseThrow(() -> new IllegalStateException("completed row vanished: " + tokenId));

        queueEngine.cleanupCompleted(queueId, token.getUserId(), tokenId, admitToken, token.getSeq());

        // 🔑 **여기선 COMPLETED 를 발행하지 않는다**(2026-09-24). 위 UPDATE 가 이미 status=2 를 커밋했고,
        //    컨슈머의 COMPLETED UPSERT 는 네 줄 전부 `status IN (0,1)` 가드라 이 행에선 **항상 no-op** 이다.
        //    발행하면 응답 경로에 동기 ack 대기(꼬리 send-timeout 12초)만 얹고 컨슈머에 헛일을 준다.
        // ❌ 위 폴백 경로와 verify 의 발행은 지우지 마라 — 거긴 DB 가 아직 모르는 완료라 이벤트가 유일한 기록이다.
        return completedAt;
    }

    /**
     * 이유: 경로가 갈리는 지점의 **분기 비율**을 남긴다.
     * 문제: 9차에서 verify 폴백 313,842회·complete 0행 97.3% 를 digest 를 떠야 알았다.
     * 원인: 어느 분기로 갔는지는 로그에도 지표에도 없었다 — HTTP 는 전부 200 이다.
     * 해결: 분기마다 카운터 1증가. 회귀가 나면 **비율이 먼저 움직인다**.
     *
     * @author sonix
     * @param name 지표 이름
     * @param tag  분기 이름(result/path)
     */
    private void countPath(String name, String tagKey, String tag) {
        Counter.builder(name).tag(tagKey, tag).register(meterRegistry).increment();
    }

    /**
     * 발행 실패를 삼키고 로그만 남긴다. 호출자 주석에 "왜 삼켜도 되는가"가 있다.
     *
     * <p>호출자는 <b>전부 단건</b>이다(verify·complete). 여러 건은 {@code publishAll} 이 맡고 첫 실패에서 끊지 않는다.
     *
     * @return 성공 여부
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
     * 이유: queue 조회 + 소유권 검증(관리용 QueueService 와 같은 패턴).
     * 🔴 <b>이 조회를 캐시하지 않는다 — {@code status} 를 매번 봐야 한다</b>(2026-09-03 확정).
     * 원인: 스테일의 대가가 비대칭 — {@code status} 가 늦으면 <b>정지시킨 큐에 사람이 계속 들어온다</b>.
     * ⚠️ 대가는 요청당 DB SELECT 1회 — 줄이려면 {@code status} 의 거처를 옮겨야 한다(§4 심사 대상).
     *
     * @author sonix
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
     * 이유: 대기 상태 폴링(FRS §6.3).
     * 문제: admit 되면 ZSet 에서 빠져 <b>정상 입장자와 없는 토큰이 구분되지 않는다</b>.
     * 해결: {@code admit-by-token} 으로 가른다 — 값이 있으면 입장권을 주고 없을 때만 404 다.
     * 🔑 Lua 가 아니라 Java 에서 보는 이유: 대기 중이 아닐 때만 돌아 <b>핫패스에 왕복이 안 는다</b>.
     * 🔴 admitToken TTL 이 지난 뒤의 404 는 <b>종료 신호</b>다 — 버그로 보고 고치면 §36 이 깨진다.
     *
     * @author sonix
     */
    public PollResult poll(String queueId, String tokenId, long seq, boolean keepalive){
        // 존재(seq)만이 아니라 소유권(tokenId)까지 검증한다. seq는 큐별 INCR이라 추측하기 쉬워서,
        // 존재만 보면 남의 대기 항목 생존 시각(last-active)을 대신 갱신할 수 있다(poll_verify.lua).
        // keepalive 인자는 하위 호환용 자리일 뿐 무시된다 — 폴링이 오면 언제나 갱신한다(§82 F안).
        String admitToken = null;
        if(!queueEngine.verifyWaiting(queueId, seq, tokenId, keepalive, clock.millis())) {
            // admitted ZSet이 아니라 admit-by-token을 본다. 유효 창은 admitToken의 PX 60초인데
            // admitted는 회수 배치가 집어갈 때까지 더 오래 남고, 돌려줄 admitToken도 여기에만 있다.
            admitToken = queueEngine.findAdmitTokenByTokenId(queueId, tokenId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));
        }

        // rank·폴링 간격은 계산하지 않는다 — 그건 /status의 lastAdmittedSeq와 pacing으로
        // 클라이언트가 한다(§79). 여기서 계산하면 응답이 사람마다 달라져 분할이 무의미해진다.
        return new PollResult(admitToken != null, admitToken);
    }

    /**
     * 이유: 큐 전광판 조회(FRS §6.3 ①). <b>인증 없음. 30만 명 전원에게 같은 응답.</b>
     * 해결: 서버가 하는 일은 {@code MGET} 3키 <b>한 왕복</b>이다 — rank 도 폴링 간격도 계산하지 않는다.
     * 원인: 개인화를 걷어내야 폴링이 {@code EVAL}(master 고정)에서 {@code MGET} 으로 바뀐다(§79).
     * 🔴 {@code @Transactional} 도 {@code findQueueAndVerifyOwner} 도 넣지 마라 — 인증 없는 최대
     *    15만/s 가 그대로 MySQL 로 간다. 큐 실재 판정은 {@code MGET} 에 실린 {@code seq} 가 한다(§79 D3).
     *
     * @author sonix
     * @throws BusinessException 큐에 enqueue 기록이 없으면 404 {@code QUEUE_NOT_FOUND}
     */
    public QueueBoard status(String queueId) {
        // 🪤 응답 캐시를 넣었다가 뺐다(2026-09-03). MGET은 448배 줄었지만 **Redis가 병목이
        //    아니라** p99는 20%만 좋아졌다 — §4의 "안 만들면 뭐가 깨지나"에 답이 없다.
        //    실측은 FRS §13. 목표 규모(≈30,900 rps)를 재고 필요가 확인되면 되살려라.
        return queueEngine.readStatus(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
    }

}
