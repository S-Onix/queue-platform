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
 * <p><b>토픽은 {@code token-lifecycle} 하나다</b>(§73 D16). Kafka의 순서 보장이
 * <b>같은 토픽의 같은 파티션</b> 안에서만 성립하므로, 상태 전이를 다른 토픽으로 나누면 키를
 * {@code tokenId}로 잡아도 {@code WAITING → ADMIT_ISSUED} 순서가 깨진다.
 *
 * <p><b>🔴 새 이벤트 타입을 낼 때는 이 컨슈머를 먼저 배포한다.</b> {@code JsonDeserializer}는
 * 모르는 필드를 무시하고 {@code spring.json.value.default.type}으로 역직렬화하므로, 구 컨슈머는
 * 새 타입을 <b>예외 없이</b> enqueue로 해석해 조용히 적재한다. 판별 필드를 본문에 둔 것만으로는
 * 못 막고 <b>읽는 쪽이 먼저 떠 있어야</b> 막힌다.
 *
 * <p><b>수동 ack을 쓰지 않는다.</b> 기본값({@code AckMode.BATCH})은 리스너가 정상 반환한 뒤
 * 커밋하고, 이 메서드는 트랜잭션 커밋 후에 반환하므로 "DB 커밋 후 ack"이 이미 성립한다.
 * 수동 ack은 실패 경로의 오프셋 관리를 리스너와 에러 핸들러가 나눠 갖게 해 DLT 처리와 어긋난다.
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
     * <p><b>🔴 같은 {@code tokenId}가 두 번 이상 있는 배치는 타입별로 모으지 않는다.</b> 모으면
     * 파티션이 지켜준 도착 순서를 애플리케이션이 다시 어긴다. 예: COMPLETED를 ADMITTED보다 먼저
     * 적용하면 가드({@code status = 1}에서만)가 거짓이라 <b>조용히 no-op</b>이 되고, 뒤이은
     * ADMITTED가 1로 만들어 그 토큰은 영원히 완료되지 않는다. 중복이 없으면 뒤집힐 두 이벤트가
     * 없으므로 그 위험 자체가 없다 — {@code canGroupByType}이 참인 경우가 그것이다.
     *
     * <p>제약 위반 외의 예외는 그대로 전파한다 → 에러 핸들러가 재시도한다.
     *
     * <p><b>모르는 타입은 그 앞까지 적재한 뒤 한 건만 격리한다.</b> 잘라내는 순서가 중요하다 —
     * 적재보다 먼저 던지면 앞쪽 정상 건들이 <b>적재되지 않은 채 커밋</b>된다
     * ({@link BatchListenerFailedException}은 인덱스 앞을 "성공"으로 간주한다).
     */
    @KafkaListener(topics = "${queue.consumer.topic:token-lifecycle}")
    public void consume(List<EnqueueEvent> events) {
        // 왜 모으는가: 구간 분할은 타입이 바뀔 때마다 트랜잭션을 연다. 타입이 섞인 배치는
        // 구간이 잘게 부서져 커밋이 폭주하고, 그 랙이 complete를 죽였다. 타입은 4종뿐이라
        // 모으면 배치당 최대 4회다. 절감 83.0%(실측) — 표·측정 함정은 doc/perf/CONSUMER_BATCHING.md.
        // 🪤 이득은 **파티션당 유입률의 함수**라 저부하에서는 정확히 0이다. 저부하 벤치로
        //    "효과 없다"고 판단해 지우지 마라.
        // ⚠️ 이건 위 금지의 예외가 아니라 **그 금지가 필요 없는 배치만 우회**하는 것이다.
        if (canGroupByType(events)) {
            try {
                int types = persistGrouped(events);
                // 반사실: 같은 배치를 분할 경로로 태웠다면 열렸을 트랜잭션 수.
                // 이게 types와 같으면 그룹 적재가 줄인 커밋이 0이다 — 값을 하는지의 유일한 직접 근거다.
                int wouldSplit = countSegments(events);
                // 🪤 이 줄이 그룹 적재의 값을 재는 **유일한 직접 근거**(tx 대 splitTx)다.
                //    다시 잴 때는 반드시 INFO로 올려라 — 유도값으로 대체하면 8배 어긋난다
                //    (doc/perf/CONSUMER_BATCHING.md). 상시 INFO는 13분에 1만 줄이라 debug다.
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
     * <p><b>🔴 {@code ZoneOffset.UTC}를 {@code systemDefault()}나 {@code now()}로 "단순화"하지
     * 마라.</b> 둘이 함께 깨진다.
     * <ul>
     *   <li><b>멱등성</b> — 이 값은 {@code UNIQUE (token_id, issued_at)}의 절반이라 재처리 때
     *       1ms만 어긋나도 다른 행이 된다. 고정 오프셋이라야 어디서 몇 번을 재처리하든 같다</li>
     *   <li><b>컬럼 규약</b> — 시각 컬럼은 전부 UTC다({@code doc/schema.sql} [시각 규약], §77).
     *       이 메서드만 이벤트가 실어 온 {@code Instant}를 변환하므로 존을 명시해야 한다</li>
     * </ul>
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
