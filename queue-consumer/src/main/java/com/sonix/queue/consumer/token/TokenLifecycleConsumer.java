package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 토큰 생명주기 이벤트 → tokens 테이블 적재.
 *
 * <p><b>왜 토픽 이름이 {@code enqueue-events}가 아닌가:</b> 같은 토큰의 상태 전이
 * (admit·complete·cancel·expire)가 이 토픽에 함께 실릴 예정이기 때문이다. Kafka의 순서
 * 보장은 <b>같은 토픽의 같은 파티션</b> 안에서만 성립하므로, 토픽을 나누면 파티션 키를
 * {@code tokenId}로 잡아도 {@code WAITING → ADMIT_ISSUED} 순서가 보장되지 않는다.
 * 지금은 enqueue 이벤트만 흐르지만 이름을 미리 맞춰 두어 나중에 옮길 일을 없앤다.
 *
 * <p><b>🔴 배포 순서: 이 컨슈머가 먼저, 새 이벤트를 발행하는 프로듀서가 나중이다.</b>
 * 반대로 하면 판별 필드를 모르는 구 컨슈머가 admit 이벤트를 받는데, Spring의
 * {@code JsonDeserializer}는 모르는 필드를 무시하고 {@code spring.json.value.default.type}으로
 * 역직렬화하므로 <b>예외 없이</b> enqueue로 해석돼 {@code Token.issue()}로 조용히 적재된다.
 * 판별 필드를 본문에 둔 이유가 이것이고(§80이 Kafka 헤더 방식을 기각한 이유와 같다),
 * 필드를 넣는 것만으로는 이 함정을 못 막는다 — <b>읽는 쪽이 먼저 떠 있어야</b> 막힌다.
 * (판별 필드 도입 자체는 순서 무관하다: 구 컨슈머는 {@code eventType}을 무시하고,
 * 새 컨슈머는 필드 없는 구 메시지를 {@code ENQUEUED}로 읽는다. 순서가 문제되는 것은
 * <b>새 타입을 실제로 발행하기 시작하는</b> Sprint 7 배포다.)
 *
 * <p><b>수동 ack을 쓰지 않는 이유:</b> 컨테이너 기본값({@code AckMode.BATCH})은 리스너가
 * <b>정상 반환한 뒤에</b> 오프셋을 커밋한다. 이 메서드는 트랜잭션이 커밋된 뒤에야 반환하므로
 * "DB 커밋 후 ack"이라는 원래 의도가 그대로 성립한다. 오히려 수동 ack은 실패 경로에서
 * 오프셋 관리를 리스너와 에러 핸들러가 나눠 갖게 만들어 DLT 처리와 어긋난다.
 */
@Slf4j
@Component
public class TokenLifecycleConsumer {

    private final TokenPersistService tokenPersistService;

    public TokenLifecycleConsumer(TokenPersistService tokenPersistService) {
        this.tokenPersistService = tokenPersistService;
    }

    /**
     * 한 번의 poll로 받은 배치를 <b>같은 타입이 연속하는 구간(run)</b>씩 적재한다.
     *
     * <p>배치 안에는 여러 파티션의 레코드가 섞여 오지만 문제되지 않는다. 파티션 사이의
     * 순서는 의미가 없고(서로 다른 토큰이므로), 같은 파티션 안의 상대 순서는 리스트에
     * 그대로 유지된다.
     *
     * <p><b>🔴 같은 {@code tokenId}가 두 번 이상 있는 배치를 타입별로 모으지 말 것.</b> 그러면 파티션이 지켜준 같은 토큰의 순서가
     * 애플리케이션에서 다시 깨진다. 한 배치에 {@code [ADMITTED tok1, COMPLETED tok1]}이 있을 때
     * COMPLETED를 먼저 적용하면 그 가드({@code status = 1}에서만)가 거짓이라 <b>조용히 no-op</b>이
     * 되고, 이어서 ADMITTED가 1로 만든다 — 그 토큰은 영원히 완료되지 않는다. 구간을 나누면
     * 도착 순서가 그대로 보존된다. 정상 운영에서는 대부분 ENQUEUED 하나라 구간도 하나다.
     *
     * <p>🔑 <b>중복 {@code tokenId}가 없으면 그 위험 자체가 없다</b> — 뒤집힐 두 이벤트가 없기
     * 때문이다. 그 경우에만 {@code canGroupByType}이 참이 되어 타입별로 모은다. 구간 분할은
     * 지워지지 않았고, 중복이 하나라도 있으면 그대로 내려간다(실측: 배치 9,443개 중 2개, 0.02%).
     *
     * <p>⚠️ 위 반례를 정확히 읽어라. {@code [ADMITTED tok1, COMPLETED tok1]}은 <b>그룹 경로에서도
     * 뒤집히지 않는다</b> — {@code EnumMap} 순회가 ADMITTED를 먼저 내보내기 때문이다. 중복 가드가
     * 실제로 막는 것은 <b>도착 순서가 상태 전이 순서와 어긋난 배치</b>(재전달이 끼어드는 등)다.
     * 그런 배치에서 타입별로 모으면 애플리케이션이 파티션의 도착 순서를 다시 어기게 된다.
     *
     * <p>제약 위반 외의 예외는 그대로 전파한다 → 에러 핸들러가 재시도한다.
     *
     * <p><b>모르는 타입은 그 앞까지 적재한 뒤 한 건만 격리한다.</b> 잘라내는 순서가 중요하다 —
     * 적재보다 먼저 던지면 앞쪽 정상 건들이 <b>적재되지 않은 채 커밋</b>된다
     * ({@link BatchListenerFailedException}은 인덱스 앞을 "성공"으로 간주한다).
     */
    @KafkaListener(topics = "${queue.consumer.topic:token-lifecycle}")
    public void consume(List<EnqueueEvent> events) {
        // 위 주석의 반례(`[ADMITTED tok1, COMPLETED tok1]`)는 **같은 tokenId가 한 배치에 두 번**
        // 나올 때만 성립한다. 없으면 타입별로 모아도 도착 순서가 깨질 대상 자체가 없다.
        //
        // 왜 모으는가: 구간 분할은 타입이 바뀔 때마다 트랜잭션을 연다. 타입이 섞인 배치는
        // 구간이 잘게 부서져 커밋이 폭주하고, 그 랙이 complete를 죽였다.
        // 타입은 4종뿐이므로 모으면 배치당 최대 4회다.
        //
        // 🔑 **이득은 파티션당 유입률의 함수다. 저부하에서는 정확히 0이다.**
        //    2026-08-27 실측(반사실 splitTx 직접 측정, 전 구간 32만 이벤트):
        //      파티션당 105~113건/s → 배치 73~158건 → 절감 84~96%
        //      파티션당   2~3건/s   → 배치  5~ 10건 → 절감 **0%**
        //      전체 합계: 커밋 76,806 → 13,080 (절감 83.0%)
        //    파티션당 유입이 낮으면 한 배치에 타입이 한 종류뿐이라 분할 경로도 트랜잭션을
        //    하나만 연다 — 줄일 것이 없다. 대략 **파티션당 50건/s**가 손익 경계다.
        //
        // 🪤 **저부하 벤치로 "효과 없다"고 판단해 지우지 말 것.** 위 0% 구간이 정상이다.
        //    되돌리려면 파티션당 50건/s 이상을 걸고 splitTx/tx를 다시 재라. 로컬에서 그 부하를
        //    내려면 부하 도구가 서버를 굶기지 않아야 한다(하니스가 죽은 판의 유입은 파티션당
        //    10건/s까지 눌려 절감이 0으로 보였다 — 코드가 아니라 측정이 틀린 것이었다).
        //
        // ⚠️ 이건 위 금지의 예외가 아니라 **그 금지가 필요 없는 배치만 우회**하는 것이다.
        //    중복이 하나라도 있으면 아래 구간 분할로 내려간다 (실측 9,443개 중 2개).
        if (canGroupByType(events)) {
            try {
                int types = persistGrouped(events);
                // 반사실: 같은 배치를 분할 경로로 태웠다면 열렸을 트랜잭션 수.
                // 이게 types와 같으면 그룹 적재가 줄인 커밋이 0이다 — 값을 하는지의 유일한 직접 근거다.
                int wouldSplit = countSegments(events);
                // 🔑 **debug인 이유와, 올려야 할 때.**
                // 이 줄은 그룹 적재가 값을 하는지 재는 **유일한 직접 근거**다(tx 대 splitTx).
                // 2026-08-27 측정은 끝났다 — 절감 83.0% / 82.8%로 두 판 재현했다. 상시로 두면
                // 13분에 1만 줄이라 상용에서는 그 자체가 부하다(로드 테스트 로그 억제 전례).
                //
                // 🪤 **다시 재려면 반드시 이 줄을 INFO로 올리고 재라.** 2026-08-27 첫 판은 이게
                //    debug라 0건이 찍혀 커밋 수를 ReplicationRoutingDataSource 로그로 **유도**했고,
                //    그 유도값(2.33)이 실측(18.5)과 8배 어긋나 판정이 하루 밀렸다.
                //    올리는 법: --logging.level.com.sonix.queue.consumer.token=DEBUG
                log.debug("token-lifecycle 적재 완료: path=grouped events={} tx={} splitTx={}", events.size(), types, wouldSplit);
                return;
            } catch (DataIntegrityViolationException e) {
                // 격리(BatchListenerFailedException)의 인덱스는 **원본 배치 기준**이라
                // 그룹 경로에서는 특정할 수 없다. 적재가 멱등이므로(ODKU) 구간 분할 경로로
                // 다시 태워 기존 격리 로직이 범인을 찾게 한다.
                log.warn("그룹 적재가 제약 위반으로 실패했다({}건) — 구간 분할 경로로 재시도한다", events.size());
            }
        }

        int start = 0;
        int segments = 0;

        while (start < events.size()) {
            TokenEventType type = TokenEventType.from(events.get(start).eventType());
            if (type == null) {
                throw unknownType(events.get(start), start);   // 앞 구간은 이미 적재된 뒤다
            }

            int end = start + 1;
            while (end < events.size() && TokenEventType.from(events.get(end).eventType()) == type) {
                end++;
            }

            List<Token> tokens = events.subList(start, end).stream()
                    .map(TokenLifecycleConsumer::toToken)
                    .toList();
            try {
                tokenPersistService.persist(type, tokens);
            } catch (DataIntegrityViolationException e) {
                quarantineOffender(type, tokens, start, e);   // 던지거나(인덱스 포함), 전 건 적재됐으면 반환
            }

            start = end;
            segments++;
        }

        log.debug("token-lifecycle 적재 완료: path=split events={} tx={} splitTx={}", events.size(), segments, segments);
    }

    /**
     * 같은 타입이 연속하는 <b>구간의 개수</b> — 분할 경로가 여는 트랜잭션 수와 같다.
     *
     * <p>그룹 경로에서 <b>반사실</b>로만 쓴다. 이 값이 실제 타입 수와 같으면 그룹 적재가
     * 줄인 커밋이 0이라는 뜻이다.
     */
    private static int countSegments(List<EnqueueEvent> events) {
        int segments = 0;
        String prev = null;
        for (EnqueueEvent e : events) {
            if (!e.eventType().equals(prev)) {
                segments++;
                prev = e.eventType();
            }
        }
        return segments;
    }

    /**
     * 이 배치를 타입별로 모아도 안전한가.
     *
     * <p>둘 다 만족해야 한다 — <b>모르는 타입이 없을 것</b>(있으면 격리 인덱스가 필요하다),
     * <b>같은 {@code tokenId}가 두 번 이상 없을 것</b>(있으면 도착 순서를 지켜야 한다).
     */
    private static boolean canGroupByType(List<EnqueueEvent> events) {
        Set<String> seen = new HashSet<>(Math.max(16, events.size() * 2));
        for (EnqueueEvent e : events) {
            if (TokenEventType.from(e.eventType()) == null) {
                return false;
            }
            if (!seen.add(e.tokenId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 타입별로 모아 타입당 한 번씩 적재한다.
     *
     * <p>{@link EnumMap}은 <b>enum 선언 순서</b>로 순회한다. {@code TokenEventType}의 선언 순서가
     * {@code ENQUEUED → ADMITTED → COMPLETED → EXPIRED}, 즉 상태 전이 순서와 같으므로 그룹 실행
     * 순서도 자연스럽게 그 방향이다. <b>선언 순서를 바꾸면 이 안전성이 조용히 깨진다.</b>
     * (중복 tokenId가 없다는 것이 이미 보장돼 있어 순서가 결과를 바꾸지는 않지만,
     * 판정에 결함이 생겼을 때의 피해를 줄이는 방향으로 고정해 둔다.)
     */
    private int persistGrouped(List<EnqueueEvent> events) {
        Map<TokenEventType, List<Token>> byType = new EnumMap<>(TokenEventType.class);
        for (EnqueueEvent e : events) {
            byType.computeIfAbsent(TokenEventType.from(e.eventType()), k -> new ArrayList<>())
                    .add(toToken(e));
        }
        byType.forEach(tokenPersistService::persist);
        return byType.size();
    }

    /**
     * 모르는 타입 <b>한 건</b>을 DLT로 보낸다 (구 컨슈머 + 신규 프로듀서, 또는 잘못된 발행).
     *
     * <p><b>삼키지 않는다.</b> 로그만 남기고 넘어가면 이벤트가 사라지고, 나중에 처리기를 붙여도
     * 되돌릴 원본이 없다. DLT에 있으면 재투입할 수 있다.
     * <b>배치 전체를 보내지도 않는다</b> — 인덱스를 실어 그 한 건만 보낸다
     * ({@link #quarantineOffender}의 설명과 같은 이유).
     *
     * <p>여기서 던지는 예외는 <b>cause가 없다.</b> 그래서 에러 핸들러의 재시도 분류가 붙잡을
     * 것이 없어, {@code BatchListenerFailedException} 자체를 재시도 제외 목록에 넣어야 한다
     * ({@code KafkaConsumerConfig}). 안 넣으면 한 건마다 백오프를 다 태우고서야 DLT로 간다.
     */
    private static BatchListenerFailedException unknownType(EnqueueEvent event, int index) {
        log.error("알 수 없는 이벤트 타입 격리 eventType={} tokenId={} queueId={} index={}",
                event.eventType(), event.tokenId(), event.queueId(), index);
        return new BatchListenerFailedException("알 수 없는 이벤트 타입: " + event.eventType(), index);
    }

    /**
     * 배치 어딘가에 못 넣는 항목이 있다. 누구인지 찾아 그 한 건만 격리시킨다.
     *
     * <p>{@link BatchListenerFailedException}에 인덱스를 실어 보내면 Spring이 <b>그 레코드만</b>
     * DLT로 보내고 앞쪽은 커밋, 뒤쪽은 재처리한다. 인덱스 없이 던지면 <b>배치 전체가 DLT로</b>
     * 넘어가 멀쩡한 수백 건이 함께 버려진다 — 인덱스를 찾는 수고를 감수할 이유가 여기 있다.
     *
     * <p>범인을 못 찾았다면(하위 묶음이 전부 성공) 제약 위반이 <b>배치 내 조합</b> 때문이었거나
     * 이미 해소된 것이다. 그리고 하위 묶음이 전부 성공했다는 것은 <b>전 건이 적재됐다</b>는
     * 뜻이므로(각 시도가 독립 트랜잭션이라 성공한 묶음은 커밋돼 있다) 정상 반환해 ack 한다.
     *
     * <p>여기서 원래 예외를 다시 올리면 안 된다. {@code DataIntegrityViolationException}은
     * 재시도 대상에서 빠져 있어(에러 핸들러 설정) 곧장 격리로 넘어가는데, 인덱스가 없으니
     * <b>이미 적재를 마친 배치가 통째로 DLT로</b> 간다.
     *
     * @param offset 이 구간이 배치에서 시작하는 위치. 인덱스는 <b>배치 전체 기준</b>이어야 한다
     */
    private void quarantineOffender(TokenEventType type, List<Token> tokens, int offset,
                                    DataIntegrityViolationException cause) {
        int index = findOffendingIndex(type, tokens, offset);
        if (index < 0) {
            log.warn("제약 위반이 났지만 범인을 특정하지 못했다({}건) — 재시도 중 전 건 적재됨", tokens.size());
            return;
        }

        Token offender = tokens.get(index - offset);
        log.error("적재 불가 항목 격리 tokenId={} queueId={} index={}",
                offender.getTokenId(), offender.getQueueId(), index);
        throw new BatchListenerFailedException("적재 불가 항목", cause, index);
    }

    /**
     * 범인을 <b>이분 탐색</b>으로 찾는다: 반으로 갈라 각각 넣어보고, 실패한 쪽만 다시 가른다.
     *
     * <p><b>왜 한 건씩이 아닌가:</b> 500건 중 1건이 문제면 건별 시도는 500번 왕복이다.
     * 이분 탐색은 성공한 절반을 통째로 배치 적재하므로 {@code log2(500) ≈ 9}단계면 끝나고
     * <b>배치의 이점을 유지</b>한다.
     *
     * <p>탐색 도중 성공한 묶음은 커밋된 채로 남지만 무해하다 — 적재가 멱등이라 나중에 같은
     * 레코드가 재처리돼도 결과가 같다.
     *
     * <p>각 시도가 별도 트랜잭션이어야 하므로 반드시 <b>프록시를 거쳐</b>
     * {@link TokenPersistService}를 호출한다. 실패한 트랜잭션 안에서 다음 시도를 하면
     * 롤백 표시가 남아 있어 무엇을 넣든 실패한다.
     *
     * @return 적재 불가 항목의 인덱스(배치 전체 기준). 이 묶음에 범인이 없으면 -1
     *         (-1은 곧 <b>이 묶음이 전부 적재됐다</b>는 뜻이다 — 호출자가 이 보장에 기댄다)
     */
    private int findOffendingIndex(TokenEventType type, List<Token> tokens, int offset) {
        if (tokens.isEmpty()) return -1;

        try {
            tokenPersistService.persist(type, tokens);
            return -1;                                  // 이 묶음엔 범인이 없다
        } catch (DataIntegrityViolationException e) {
            if (tokens.size() == 1) return offset;      // 혼자 넣어도 실패 → 범인 확정
        }

        int mid = tokens.size() / 2;
        int found = findOffendingIndex(type, tokens.subList(0, mid), offset);
        return found >= 0
                ? found
                : findOffendingIndex(type, tokens.subList(mid, tokens.size()), offset + mid);
    }

    /**
     * 이벤트 → 도메인 토큰.
     *
     * <p>{@code issuedAt}을 UTC로 고정 변환하는 것이 중요하다. 이유가 둘이다.
     *
     * <p><b>1) 멱등성.</b> 이 값은 {@code UNIQUE (token_id, issued_at)}의 절반이라
     * 재처리 때 1ms만 어긋나도 다른 행이 되어 멱등 적재가 깨진다.
     * {@code ZoneOffset.UTC}는 고정 오프셋이라 어느 서버에서 몇 번을 재처리하든 같은 값이 나온다.
     * {@code ZoneId.systemDefault()}로 바꾸면 인스턴스별 TZ 설정에 따라 갈리고,
     * DST가 있는 지역에서는 같은 인스턴스에서도 갈린다.
     *
     * <p><b>2) 컬럼 규약.</b> 이 프로젝트의 시각 컬럼은 전부 UTC다
     * ({@code doc/schema.sql}의 [시각 규약], DECISIONS §77). 다른 경로는 JVM 기본 TZ가 UTC라
     * {@code LocalDateTime.now()}만으로 UTC가 되지만, <b>이 메서드만은 이벤트가 실어 온
     * {@code Instant}를 변환</b>하므로 존을 명시해야 한다. 여기서 {@code systemDefault()}를 쓰면
     * JVM TZ 설정에 결과가 좌우돼 (1)의 멱등성이 함께 깨진다.
     *
     * <p>따라서 이 줄을 {@code LocalDateTime.now()}나 {@code ofInstant(..., systemDefault())}로
     * "단순화"하면 안 된다. 멱등성과 컬럼 규약이 동시에 깨진다.
     */
    private static Token toToken(EnqueueEvent e) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(e.issuedAt(), ZoneOffset.UTC);
        // admittedAt도 같은 규약으로 변환한다. 이 값은 verify·complete의 유효 창 기준이라
        // 인스턴스 TZ에 따라 갈리면 60초 창이 서버마다 다른 뜻이 된다.
        LocalDateTime admittedAt = e.admittedAt() == null
                ? null
                : LocalDateTime.ofInstant(e.admittedAt(), ZoneOffset.UTC);

        return Token.transition(TokenEventType.from(e.eventType()).targetStatus(),
                e.tokenId(), e.queueId(), e.tenantId(), e.userId(), e.seq(),
                issuedAt, e.admitToken(), admittedAt, e.expiredReason());
    }
}
