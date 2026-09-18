package com.sonix.queue.api.queue;

import com.sonix.queue.common.exception.BusinessException;
import com.sonix.queue.common.exception.ErrorCode;
import com.sonix.queue.domain.queue.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
     * {@code queue_admission_wait_seconds}의 버킷 경계 (MONITORING_DESIGN 4-3).
     *
     * <p><b>여기 한 곳에만 있다.</b> 경계는 SLO 판단이라 바뀔 값이고(경고 p95 1분 · 위험 5분이
     * 60·300에 걸려 있다), 흩어 놓으면 알람과 어긋난다. 늘리면 시계열이 큐당 버킷 수만큼 는다.
     */
    private static final Duration[] ADMISSION_WAIT_SLO = {
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60),
            Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(600),
            Duration.ofSeconds(1800), Duration.ofSeconds(3600)
    };

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
                    .register(meterRegistry)
                    .record(System.nanoTime() - kafkaStart, java.util.concurrent.TimeUnit.NANOSECONDS);
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
     * 대기 시간 분포를 기록한다 — {@code queue_admission_wait_seconds{queue_id}} (MONITORING_DESIGN 4-3).
     *
     * <p>{@code issuedAt}("줄 선 시각")과 이번 admit 시각의 차. <b>추가 조회 0</b>이다 —
     * 두 값 모두 admit.lua 반환과 호출자가 이미 손에 쥔 것이다.
     *
     * <p><b>REPLAY는 기록하지 않는다.</b> 같은 requestId의 재시도는 <b>첫 호출과 같은 records</b>를
     * 돌려주는데, 여기서 쓸 수 있는 admit 시각은 <b>재시도 시각</b>뿐이다(멱등 payload에 시각이
     * 없다 — publishAdmitted 주석 참조). 기록하면 같은 사람이 두 번 세어지고, 그 두 번째 값은
     * 재시도가 늦은 만큼 부풀어 p95를 위로 끈다.
     *
     * <p>🪤 <b>{@code queue_id} 라벨에 전역 상한이 없다.</b> §87의 "테넌트당 20개"는 삭제된 큐를
     * 안 세고 테넌트 수도 무제한이라, 생성→삭제 반복이면 미터가 재기동 전까지 쌓인다.
     * 그래도 라벨은 뗄 수 없다 — 알람이 답해야 하는 것이 "<b>어느</b> 큐인가"다.
     * 큐가 수만 개가 되면 재검토.
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
                // 🔴 **음수를 0으로 눕히지 않는다.** 두 시각 모두 앱 시계라 N대의 스큐가 그대로
                //    들어온다(-398초 실측). clamp하면 스큐 신호가 사라지고, 그냥 record하면
                //    Timer가 음수를 조용히 버려 아무 데도 안 남는다. 빼되 카운터로 드러낸다.
                //
                // 🪤 이 미터는 **첫 스큐 때 만들어져 값 1로 태어난다.** increase()가 상수 1의 델타를
                //    0으로 내므로 스큐 1건은 그대로 두면 안 잡힌다 — 보정은 앱이 아니라
                //    alerts/app.yml의 QueueAdmissionClockSkewDetected가 unless...offset 절로 한다
                //    (recordAdmitRequest javadoc에 같은 판단의 근거가 있다).
                // 🔑 **크기까지 남긴다(2026-09-17).** 건수만 세면 "무해한 경계 잡음"과
                //    "진짜 시계 고장"을 구분할 수 없다 — 실측으로 그 상태를 확인했다.
                //    AWS 실측: 스큐 32건/475,323건(0.0067%)인데 호스트 시계 오차는 1~2µs 였다.
                //    대기가 0에 가까운 토큰의 부호가 뒤집힌 것인지, 어딘가 분 단위로 어긋난 것인지
                //    (주석 위의 -398초가 그 사례다) **알람을 받은 사람이 판별할 방법이 없었다.**
                //    Timer 로 두면 count 는 종전과 같고 sum·max 가 더 생긴다 — 미터는 안 는다.
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
     * admit 요청 1건과 발급된 토큰 수를 센다 — {@code queue_admit_requests_total{queue_id, result}} /
     * {@code queue_admit_tokens_issued_total{queue_id}} (§80 U9).
     *
     * <p><b>라벨은 {@code queue_id}다.</b> §80(DECISIONS:5822)의 {@code queueId} 표기를 따르지
     * 않는다 — {@code queue_admission_wait_seconds}가 {@code queue_id}를 쓰므로 철자가 갈리면
     * {@code and on(queue_id)} 조인이 성립하지 않는다. Micrometer는 태그 키를 snake_case로
     * 바꿔 주지 않는다 (alerts/infra.yml).
     *
     * <p><b>{@code result=error}는 발행 실패다</b>, admit 자체의 실패가 아니다. admit은 Lua가
     * 커밋된 뒤라 5xx를 줄 수 없어 <b>항상 200</b>이고(FRS §6.4), 그래서 HTTP 상태로는 절대
     * 안 보인다. 지금까지 유일한 흔적이 {@code publishAdmitted}의 ERROR 로그 한 줄이었다.
     * 발행이 빠진 토큰은 {@code admitted_at}이 NULL로 남아 <b>complete가 영구 404</b>가 된다.
     *
     * <p><b>REPLAY는 토큰을 세지 않는다.</b> 재시도는 첫 호출과 같은 records를 돌려줄 뿐 새로
     * 발급하지 않는다. 세면 같은 토큰이 두 번 잡힌다 (recordAdmissionWait가 REPLAY를 빼는 것과
     * 같은 이유). 대신 {@code result=replay}로 요청 자체는 남으므로 잃는 정보가 없다.
     *
     * <p>🔴 <b>REPLAY가 {@code error}보다 우선한다 — 순서를 뒤집지 마라.</b> {@code issuedAt}이
     * null이라 발행을 건너뛰는 <b>유일한 실제 경로가 REPLAY다</b>(구 포맷 멱등 payload,
     * {@code RedisQueueEngine.parseAdmitResult} javadoc). 그 토큰은 <b>첫 호출에서 이미 발행돼</b>
     * {@code admitted_at}이 차 있으므로 404가 아닌데, error로 접으면 critical 알람이 뜨고 런북이
     * 멀쩡한 행을 손으로 고치라고 시킨다. 첫 호출의 발행이 진짜로 실패했다면 <b>그때 error로
     * 이미 세어졌다</b> — 뒤집어도 얻는 것이 없고 {@code replay} 카운트만 영영 0이 된다.
     *
     * <p>🪤 <b>미터는 지연 등록된다 — 시계열이 값 1로 태어난다.</b> Micrometer가 첫 호출 때
     * 미터를 만들기 때문이고, 그러면 {@code increase()}는 구간 첫 표본을 기준선으로 삼아 상수 1의
     * 델타를 0으로 낸다("0으로 외삽" 보정은 델타 &gt; 0일 때만 걸린다).
     * <b>이 보정은 앱이 하지 않는다</b> — {@code alerts/app.yml}의 {@code QueueAdmitPublishFailing}이
     * {@code unless ... offset} 절로 "이번 창에 새로 생긴 시계열"을 함께 잡는다.
     * <br>여기서 result 4종을 미리 등록해 0을 심는 안을 검토했다가 <b>버렸다</b>:
     * 등록과 증가가 같은 호출 안이라 <b>그 큐의 첫 admit이 곧 실패하면 여전히 1로 태어난다</b>
     * (promtool 재현). 앱 코드가 PromQL 특성을 반만 보상하면서 주석은 다 한다고 말하게 되고,
     * 시계열만 큐당 4개로 는다. <b>보정은 한 곳에서만 한다.</b>
     *
     * <p>🪤 {@code queue_id} 카디널리티는 recordAdmissionWait의 주석과 같은 조건이다.
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
     * ADMITTED 발행 — <b>실패해도 예외를 올리지 않는다</b> (FRS §6.4).
     *
     * <p>enqueue는 발행 실패에 503을 준다 — 그 시점엔 아직 아무것도 확정되지 않아 거절이
     * 성립한다. admit은 반대다. Lua가 이미 커밋됐고(대기열에서 빠졌고 admitToken도 나갔다)
     * 되돌릴 수 없다. 5xx를 주면 재시도가 {@code admit-idem} REPLAY로 같은 답만 받는 무한
     * 반복이 된다. 미반영의 피해는 complete가 {@code status IN (0,1)}로 관대해 흡수한다.
     *
     * <p><b>REPLAY도 발행한다.</b> 컨슈머 UPSERT가 멱등이라 중복은 무해한 반면, 첫 호출에서
     * 발행이 실패했을 때 재시도가 그것을 <b>복구</b>할 수 있는 유일한 경로다.
     *
     * <p>⚠️ <b>REPLAY의 {@code admittedAt}은 재시도 시각이다</b>(멱등 payload에 시각이 없다).
     * <b>§90 이후 이 값 자체는 적재되지 않는다</b> — 컬럼은 {@code UTC_TIMESTAMP(3)}가 찍고 이벤트는
     * null 여부만 준다. 그래서 "재시도 시각이라 유효 창이 밀린다"는 대가가 <b>없다</b>.
     * 여기서 non-null을 실어야 하는 이유는 시각이 아니라 <b>"admit이 일어났다"는 표지</b>이기 때문이고,
     * null을 실으면 첫 발행이 실패했을 때 복구 경로가 {@code admitted_at}을 NULL로 남겨
     * complete가 영구 404가 된다.
     *
     * <p>🔴 <b>첫 발행 실패에서 끊는다.</b> 발행은 건별 {@code .get(12초)} 블로킹이라, 브로커가
     * 무응답이면 {@code count=100}짜리 admit 한 건이 <b>최대 20분</b> 동안 요청 스레드를 잡는다.
     * 첫 건이 시한을 다 쓰고 실패했다면 나머지 99건도 같은 브로커를 기다릴 뿐이다.
     *
     * <p>⚠️ <b>건너뛴 분은 자동 복구되지 않는다.</b> admit은 발행이 실패해도 200이라 Tenant에게
     * 재시도할 이유가 없다 — REPLAY 복구는 가능성이지 경로가 아니다. 그래서 건너뛴 건수와 첫
     * tokenId를 ERROR로 남긴다(유일한 흔적이다). 병렬 발행은 답이 아니다 — 메타데이터가 없으면
     * {@code send()} 자체가 블로킹이라 스레드만 늘고 벽시계는 그대로다.
     *
     * @return 발행하지 못한 건수. 0이 아니면 그만큼 {@code admitted_at}이 NULL로 남아
     *         complete가 영구 404가 되므로, 호출자가 {@code result=error}로 계측한다 (§80 U9).
     */
    private int publishAdmitted(long tenantId, String queueId, AdmitResult result, Instant admittedAt) {
        List<AdmitResult.AdmitRecord> records = result.records();
        int skipped = 0;
        for (int i = 0; i < records.size(); i++) {
            AdmitResult.AdmitRecord record = records.get(i);
            if (record.issuedAt() == null) {
                skipped++;
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
                skipped += records.size() - i;
                log.error("ADMITTED 발행 중단 queueId={} 건너뜀={}건 첫tokenId={}",
                        queueId, records.size() - i, record.tokenId());
                break;
            }
        }
        return skipped;
    }

    /**
     * Verify — admitToken이 지금 유효한지 답하고, <b>그 응답이 곧 완료다</b> (FRS §6.5 · PR #48).
     * <b>DB 쓰기 0회.</b> Redis는 회차 키 넷을 정리하되 {@code admit-by-admit}은 남긴다(§92) — 그래서
     * 같은 admitToken의 verify는 60초 안에 계속 통과한다(재시도 계약). {@code COMPLETED}는 응답과 함께 발행한다.
     *
     * @return identifier (Tenant가 어느 사용자인지 알아야 하므로)
     * @throws BusinessException 유효하지 않으면 404 {@code INVALID_ADMIT_TOKEN}
     */
    // 🔴 **트랜잭션을 걸지 않는다.** verify는 Kafka를 동기로 기다리는데(send-timeout 12초),
    //    트랜잭션 안이면 커넥션을 그 끝까지 쥔다. verify는 게이트 개방 순간 입장자 수만큼
    //    몰리는 엔드포인트라 그게 곧 자해다. Redis 히트 경로는 DB를 아예 안 읽는다.
    //
    //    🪤 **폴백 조회는 master로 간다.** 라우팅을 가르는 것은 메서드가 아니라 **readOnly
    //       트랜잭션이 열렸는가**다(CLAUDE.md §4-3). 트랜잭션 없이 부른 파생 쿼리는 전부 master다.
    //       → 안 거는 판단은 유지한다. 대가가 replica 풀이 아니라 **master 풀 점유**일 뿐이다.
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
            // 🔑 **verify 응답을 주는 시점이 완료다.** Platform의 책임은 답을 돌려주는 데까지이고,
            //    그 뒤 Tenant 안에서 좌석 배정·세션 생성이 어떻게 되는지는 관측할 수도 책임질 수도
            //    없다 (CLAUDE.md 원칙 1 — Platform은 순서만, Tenant가 입장 제어).
            //    이 전이가 없으면 complete를 안 부르는 Tenant의 행이 status=1로 영원히 남는다.
            //
            //    🔴 **DB를 직접 쓰지 않고 이벤트만 발행한다.** 근거 둘: ① 쓰기 트랜잭션을 열면
            //    Redis 히트로 끝나는 정상 경로까지 Master 커넥션을 잡아 "verify는 DB를 안 읽는다"는
            //    설계(§6.4)가 되돌아간다. ② 여기서 UPDATE하면 Kafka 소비 경로와 두 갈래로 갈려
            //    순서 보장이 사라진다 — 파티션 키가 tokenId라 이벤트 경로는 컨슈머 백로그
            //    구간에도 ADMITTED 다음에 이 전이가 얹히는 것이 보장된다.
            //    🔴 **@Transactional(readOnly)를 붙이지 마라** — verify가 Kafka 12초 동안
            //       커넥션을 쥐는 재발 경로다.
            //
            // 🔴 **Redis는 정리한다 — 단 admit-by-admit은 남긴다 (§92).** "verify는 Redis 쓰기 0회"였던
            //    시절(§80)은 verify가 완료가 아니었다. 완료 확정 주체가 여기로 옮겨온 뒤(PR #48)에도
            //    정리는 complete에만 있어, verify만 부르는 Tenant의 완료자가 최대 70초 동안 옛 토큰으로
            //    줄 없이 재입장했고 과금이 경로에 따라 1 vs 2로 갈렸다(2026-09-11 실측).
            //    admit-by-admit을 남기는 이유는 아래 complete()의 Redis 폴백과 Tenant의 verify 재시도가
            //    그 키 하나에 기대기 때문이다 — 지우면 둘 다 404. 순서는 complete와 같이 정리 → 발행.
            //    구 포맷(complete()==false)은 seq가 없어 admitted 멤버를 못 지우므로 건너뛴다 —
            //    그 잔여는 회수 배치가 ≤70초 안에 걷는다(롤링 배포 60초 구간의 동작).
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

            // 🔑 **여기까지 왔다 = DB가 이 토큰을 아직 모른다.** markCompleted가 요구하는
            //    admit_token·admitted_at은 컨슈머가 ADMITTED를 적재해야 채워진다 —
            //    랙 구간에서는 정상 입장자도 0행이다.
            //
            // 🔑 **폴백의 근거는 실측이 아니라 불변식이다.** admit-by-admit의 PX 60초 ⊂ DB 창
            //    300초. 그래서 이 폴백이 통과시키는 요청은 적재만 끝났다면 위 UPDATE도
            //    통과시켰을 것들뿐이고 자격이 넓어지지 않는다.
            //    (두 상수가 갈리면 불변식이 깨진다 — Token.COMPLETE_VALID_WINDOW_SECONDS와
            //     admit TTL을 같이 보고 고쳐라.)
            //
            // 🔑 **이 불변식은 상수 둘만으로 성립한다 — 시계는 더 이상 전제가 아니다** (§90).
            //    두 창을 재는 시계: 60초는 **Redis**가 PX로 재고(상대 시간이라 시계 오차에 면역),
            //    300초는 **MySQL의 UTC_TIMESTAMP(3)** 가 `admitted_at`과 비교해 잰다.
            //    그리고 그 `admitted_at`도 이제 **MySQL이 찍는다**(TokenJpaAdapter의 ADMITTED ODKU) —
            //    비교의 양변이 같은 프로세스에서 나오므로 **어떤 앱 시계 스큐에도 창이 300초 그대로**다.
            //    게다가 `admitted_at = admit + 컨슈머랙(≥0)`이라 DB 창의 시작점이 Redis 창보다
            //    항상 뒤에 있어, 포함 관계가 **구조적으로** 성립한다.
            //
            // 🪤 **예전엔 여기가 원장 손상 경로였다** — `admitted_at`이 admit을 처리한 API 서버
            //    시계로 찍히던 시절, 그 서버가 S초 뒤처지면 DB 술어의 창이 `max(300 − S, 0)`으로
            //    줄었다(실측 2026-09-09: S=398이면 admit 0초 뒤에도 markCompleted가 0행).
            //    S > 240이면 60 ⊄ 300이 되어 이 폴백이 자격을 넓히고, 그 결과가 404보다 나빴다:
            //      ① ReconcileJob.expireStaleAdmitted가 그 행을 status=4로 확정
            //      ② 사용자는 이 폴백으로 **200**을 받고 COMPLETED가 발행됨
            //      ③ 컨슈머 가드가 `IF(tokens.status = 1, ...)`라 status=4에서 **no-op**
            //         (TokenJpaAdapter) → 행은 `status=4 / completed_at=NULL`로 **영구 고정**
            //      ④ 재시도는 폴백 키가 지워져 404 — 멱등성까지 깨졌다
            //    가용성이 아니라 **원장 무결성** 문제였고, Tenant는 200을 받아 알 수단이 없었다.
            //    🔑 **이 이력을 지우지 마라** — `admitted_at`을 이벤트 payload 값으로 되돌리거나
            //       reconcile cutoff를 다시 앱에서 계산하면 같은 사고가 그대로 재발한다.
            //       (batch 시계로 되돌리면 방향만 반대인 같은 사고다.)
            //
            // ⚠️ 남은 앱 시계는 **원장 밖**이다: Redis `admitted` ZSet score와 TokenReclaimJob은
            //    여전히 앱 시계고, 거기서 어긋나면 회수가 이르거나 늦을 뿐 되돌릴 수 있다.
            //    issued_at도 앱 시계인데 **그게 맞다** — UNIQUE(token_id, issued_at) + 파티션 키 +
            //    Kafka 재처리 멱등의 절반이라, DB 시계로 만들면 재처리마다 새 행이 생긴다.
            //
            // 🔴 **아래 WARN의 문구를 믿지 마라 — 폴백에 닿는 이유는 최소 둘이다.**
            //    ① 컨슈머 적재 지연 (WARN이 말하는 그것)
            //    ② **ADMITTED가 아직 발행조차 안 됐다** — admit.lua가 커밋되면 admitToken이 Redis에
            //       즉시 보이는데(폴링은 Redis만 본다), publishAdmitted는 그 뒤에 건별 블로킹
            //       .get()으로 **직렬** 발행한다. 그 사이에 사용자가 폴링 → verify → complete를
            //       끝내면 COMPLETED가 ADMITTED보다 **먼저** 파티션에 append된다.
            //    실측(2026-09-09, Kafka 오프셋 전수): stuck 7건 **전부** COMPLETED 오프셋 < ADMITTED
            //    오프셋이었다. ADMITTED 발행 지연 67~128ms. 폴백 1,117건 중 16건(1.43%)이
            //    COMPLETED 가드 `IF(tokens.status = 1, ...)`에서 no-op이 되어 status=1로 고착됐고,
            //    300초 뒤 ReconcileJob이 status=4 / completed_at=NULL로 확정했다.
            //    🪤 **이 WARN만 보고 Kafka·컨슈머를 조사하면 원인을 영영 못 찾는다** —
            //       ②의 경우 컨슈머 랙은 0이다. 판별은 그 tokenId의 ADMITTED·COMPLETED
            //       **오프셋 대소**로 한다. (별건 미해결)
            //
            // 🪤 시계 스큐로 여기 오던 경로는 §90으로 닫혔다. 예전 주석이 감시를
            //    HostClockNotSynchronized에 맡긴다고 적었는데, 그 알람은 `node_timex_sync_status`
            //    (= NTP 데몬이 커널을 먹이는가)를 볼 뿐이라 수동 `date -s`·스냅샷 복원·절전 복귀·
            //    NTP가 틀린 시각 배포를 **못 잡는다**. 그 신뢰가 필요 없어진 것이 §90의 요점이다.
            //
            // ⚠️ **순서를 뒤집지 마라.** Redis를 먼저 보게 만들면, publishQuietly가 발행을
            //    삼켰을 때 행이 status=1로 남고 ReconcileJob이 **완료된 토큰을 EXPIRED로
            //    확정**한다(Redis 키는 이미 지워진 뒤라 복구 경로가 없다).
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
     *
     * <p>🔴 <b>이 조회를 캐시하지 않는다 — {@code status}를 매번 봐야 하기 때문이다</b>
     * (2026-09-03 확정). 스테일의 대가가 비대칭이다:
     * <ul>
     *   <li>{@code maxCapacity}가 늦으면 → 정원 확대가 늦게 반영된다. 되돌릴 수 있다</li>
     *   <li><b>{@code status}가 늦으면 → 정지시킨 큐에 사람이 계속 들어온다.</b> 장애가 커진다</li>
     * </ul>
     * 그래서 드레인의 용량은 캐시하되({@code BatchProcessor.capacityByQueueId}) 이쪽은 안 한다.
     * {@code BatchProcessor.getMaxCapacity}의 옛 주석이 경고하던 PAUSED 문제의 주소가 여기다.
     *
     * <p>⚠️ <b>대가:</b> 요청당 1회 DB SELECT로 고정된다(2,000 rps면 초당 2,000회 —
     * 드레인이 캐시로 없앤 초당 1,000회보다 크다). 줄이려면 캐시가 아니라 {@code status}의
     * 거처를 옮겨야 하고, 그건 새 키·해시태그·전손 복구 규약이 따라오는 §4 심사 대상이다.
     * 🪤 이 부담이 실제 병목인지는 <b>미측정</b>이다.
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
     * <p>🔴 <b>admitToken TTL이 만료된 사람은 이 분기로 와서 404를 받는다. 그게 의도다(§36).</b>
     * 회수 배치가 {@code admitted}에서 빼고 {@code tokens}를 HDEL하므로 두 조회 모두 실패하고,
     * 그 404가 <b>종료 신호</b>다(재접속하면 재-enqueue라 맨 뒤다). 복귀 경로는 없다 —
     * {@code admit_expire.lua}에 {@code ZADD waiting}이 없다.
     * <b>이 404를 버그로 보고 고치면 §36 계약이 깨진다.</b>
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
        // 🪤 응답 캐시를 넣었다가 뺐다(2026-09-03). MGET은 448배 줄었지만 **Redis가 병목이
        //    아니라** p99는 20%만 좋아졌다 — §4의 "안 만들면 뭐가 깨지나"에 답이 없다.
        //    실측은 FRS §13. 목표 규모(≈30,900 rps)를 재고 필요가 확인되면 되살려라.
        return queueEngine.readStatus(queueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUEUE_NOT_FOUND));
    }

}
