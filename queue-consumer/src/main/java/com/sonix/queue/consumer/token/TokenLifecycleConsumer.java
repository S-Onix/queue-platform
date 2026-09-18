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
 * 이유: 토큰 생명주기 이벤트를 tokens 테이블에 적재한다.
 * 문제: 상태 전이를 토픽으로 나누면 키가 tokenId 여도 순서가 깨진다. 또 구 컨슈머는 모르는 타입을
 *       예외 없이 enqueue 로 해석해 조용히 적재한다(JsonDeserializer 가 모르는 필드를 무시한다).
 * 해결: 토픽은 token-lifecycle 하나(§73 D16). 🔴 새 이벤트 타입은 이 컨슈머를 먼저 배포한다.
 *       ack 은 기본값(AckMode.BATCH)이라 "DB 커밋 후 ack"이 이미 성립한다 — 수동 ack 을 넣지 마라.
 *
 * @author sonix
 */
@Slf4j
@Component
public class TokenLifecycleConsumer {

    private final TokenPersistService tokenPersistService;

    public TokenLifecycleConsumer(TokenPersistService tokenPersistService) {
        this.tokenPersistService = tokenPersistService;
    }

    /**
     * 이유: 한 poll 배치를 <b>같은 타입이 연속하는 구간</b>씩, 순서를 지켜 적재한다.
     * 문제: 타입별로 모으면 파티션이 지켜준 도착 순서를 애플리케이션이 다시 어긴다.
     * 원인: {@code ADMITTED}·{@code EXPIRED} 가 {@code status = 0} 출발 가드라 순서가 결과를 바꾼다.
     *       🔧 {@code COMPLETED} 는 §91 에서 {@code IN (0,1)} 로 넓어져 <b>순서에 의존하지 않는다</b>.
     * 해결: 중복 tokenId 가 없을 때만 모은다({@code canGroupByType}). 구간은 한 트랜잭션에 넣는다.
     *
     * @author sonix
     * @param events 한 poll 로 받은 배치. 여러 파티션이 섞여도 파티션 내 상대 순서는 유지된다
     */
    @KafkaListener(topics = "${queue.consumer.topic:token-lifecycle}")
    public void consume(List<EnqueueEvent> events) {
        // 이유: 타입별로 모으면 문장이 타입당 하나(다중행)가 된다.
        // 문제: 구간 분할은 타입이 바뀔 때마다 문장을 열어 500건 배치가 실효 1.82행이었다(실측).
        // 해결: 중복이 없는 배치만 모아 문장 수와 행/문장을 줄인다.
        // 🪤 이득은 파티션당 유입률의 함수라 저부하에서는 정확히 0이다 — 저부하 벤치로 지우지 마라.
        // 🔧 커밋 수는 더 이상 이 분기에서 갈리지 않는다(분할 경로도 한 트랜잭션). doc/perf/CONSUMER_BATCHING.md
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

        // 구간을 먼저 모으고 한 트랜잭션으로 적재한다. 지켜야 하는 것은 **문장 순서**이지
        // 트랜잭션 경계가 아니다 — 구간마다 커밋하면 500건 배치가 265~322 커밋이 된다(실측).
        // 모르는 타입에서 멈추는 것은 그대로다: 그 앞까지 적재한 **뒤에** 던져야 한다.
        List<TokenPersistService.Segment> segments = new ArrayList<>();
        int unknownAt = -1;
        int start = 0;

        while (start < events.size()) {
            TokenEventType type = TokenEventType.from(events.get(start).eventType());
            if (type == null) {
                unknownAt = start;
                break;
            }

            int end = start + 1;
            while (end < events.size() && TokenEventType.from(events.get(end).eventType()) == type) {
                end++;
            }

            segments.add(new TokenPersistService.Segment(type,
                    events.subList(start, end).stream().map(TokenLifecycleConsumer::toToken).toList(),
                    start));
            start = end;
        }

        if (!segments.isEmpty()) {
            try {
                tokenPersistService.persistAll(segments);
                // 🪤 tx는 1이고 splitTx가 구간 수다. 둘을 비교하는 것이 이 변경의 값을 재는 유일한 직접 근거다.
                log.debug("token-lifecycle 적재 완료: path=split events={} tx=1 splitTx={}",
                        events.size(), segments.size());
            } catch (DataIntegrityViolationException e) {
                // 격리의 이분 탐색은 시도마다 독립 트랜잭션이어야 한다 — 실패 경로에서만 필요한 성질이라
                // 여기서 비로소 구간별로 나눈다. 적재가 멱등이라(ODKU) 다시 태워도 결과가 같다.
                log.warn("구간 일괄 적재가 제약 위반으로 실패했다({}구간) — 구간별 독립 트랜잭션으로 재시도한다",
                        segments.size());
                persistSegmentsIndividually(segments);
                log.debug("token-lifecycle 적재 완료: path=split-fallback events={} tx={} splitTx={}",
                        events.size(), segments.size(), segments.size());
            }
        }

        if (unknownAt >= 0) {
            throw unknownType(events.get(unknownAt), unknownAt);   // 앞 구간은 이미 적재된 뒤다
        }
    }

    /** 구간마다 독립 트랜잭션으로 적재한다. 제약 위반이 난 구간에서 범인을 찾아 한 건만 격리한다. */
    private void persistSegmentsIndividually(List<TokenPersistService.Segment> segments) {
        for (TokenPersistService.Segment segment : segments) {
            try {
                tokenPersistService.persist(segment.type(), segment.tokens());
            } catch (DataIntegrityViolationException e) {
                // 던지거나(인덱스 포함), 전 건 적재됐으면 반환
                quarantineOffender(segment.type(), segment.tokens(), segment.offset(), e);
            }
        }
    }

    /**
     * 같은 타입이 연속하는 <b>구간의 개수</b> — 분할 경로가 여는 <b>문장 수</b>와 같다.
     *
     * <p>🔧 예전엔 트랜잭션 수와도 같았다. 분할 경로가 한 트랜잭션으로 바뀐 뒤로는
     * 문장 수만 가리킨다.
     *
     * <p>그룹 경로에서 <b>반사실</b>로만 쓴다. 이 값이 실제 타입 수와 같으면 그룹 적재가
     * 줄인 문장이 0이라는 뜻이다.
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
     * 이유: 타입별로 모아 타입당 한 번씩 적재한다.
     * 문제: 그룹 실행 순서가 상태 전이 순서와 어긋나면 판정에 결함이 났을 때 피해가 커진다.
     * 원인: {@link EnumMap} 은 <b>enum 선언 순서</b>로 순회한다.
     * 해결: {@code TokenEventType} 선언 순서가 ENQUEUED → ADMITTED → COMPLETED → EXPIRED 다.
     * 🪤 <b>선언 순서를 바꾸면 이 안전성이 조용히 깨진다</b>(중복이 없어 결과는 같지만).
     *
     * @author sonix
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
     * 이유: 모르는 타입 <b>한 건</b>을 DLT 로 보낸다(구 컨슈머 + 신규 프로듀서, 잘못된 발행).
     * 문제: 로그만 남기고 삼키면 이벤트가 사라져 나중에 처리기를 붙여도 되돌릴 원본이 없다.
     * 해결: 인덱스를 실어 그 한 건만 DLT 로 보낸다(배치 전체를 보내지 않는다).
     * 🪤 여기서 던지는 예외는 <b>cause 가 없다</b> — 재시도 분류가 붙잡을 것이 없으므로
     *    {@code BatchListenerFailedException} 자체를 재시도 제외 목록에 넣어야 한다.
     *
     * @author sonix
     */
    private static BatchListenerFailedException unknownType(EnqueueEvent event, int index) {
        log.error("알 수 없는 이벤트 타입 격리 eventType={} tokenId={} queueId={} index={}",
                event.eventType(), event.tokenId(), event.queueId(), index);
        return new BatchListenerFailedException("알 수 없는 이벤트 타입: " + event.eventType(), index);
    }

    /**
     * 이유: 배치에 못 넣는 항목이 있을 때 그 <b>한 건만</b> 격리한다.
     * 문제: 인덱스 없이 던지면 배치 전체가 DLT 로 가 멀쩡한 수백 건이 함께 버려진다.
     * 해결: {@link BatchListenerFailedException} 에 인덱스를 실어 그 레코드만 DLT 로 보낸다.
     * 🪤 원래 예외를 다시 올리지 마라 — 재시도 대상이 아니라 인덱스 없이 격리로 넘어간다.
     * 범인을 못 찾으면(-1) 하위 묶음이 전부 커밋됐다는 뜻이라 정상 반환해 ack 한다.
     *
     * @author sonix
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
     * 이유: 범인을 <b>이분 탐색</b>으로 찾는다 — 반으로 갈라 넣어보고 실패한 쪽만 다시 가른다.
     * 문제: 건별 시도는 500건 중 1건이 문제여도 500번 왕복이다.
     * 해결: 성공한 절반은 통째로 배치 적재하므로 log2(500)≈9 단계면 끝나고 배치 이점이 남는다.
     * 🪤 각 시도가 <b>별도 트랜잭션</b>이어야 하므로 반드시 프록시를 거쳐 호출한다 —
     *    실패한 트랜잭션 안에서 다음 시도를 하면 롤백 표시 때문에 무엇을 넣든 실패한다.
     *
     * @author sonix
     * @return 적재 불가 항목의 인덱스(배치 전체 기준). 없으면 -1 = <b>이 묶음은 전부 적재됨</b>
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
     * 이유: 이벤트를 도메인 토큰으로 옮긴다. 시각은 고정 오프셋 UTC 로 변환한다.
     * 문제: {@code systemDefault()}·{@code now()} 로 바꾸면 재처리 멱등성이 깨진다.
     * 원인: {@code issued_at} 은 {@code UNIQUE (token_id, issued_at)} 의 절반이라 1ms 도 다르면 새 행이다.
     * 해결: 존을 명시한다. 시각 컬럼은 전부 UTC 다(§77, doc/schema.sql).
     * 🔧 {@code admittedAt} 값은 DB 에 안 들어간다 — null 여부로만 쓰이고 값은 MySQL 이 찍는다(§90).
     *
     * @author sonix
     */
    private static Token toToken(EnqueueEvent e) {
        LocalDateTime issuedAt = LocalDateTime.ofInstant(e.issuedAt(), ZoneOffset.UTC);
        // 🔧 이 값은 컬럼에 안 들어간다 — null 여부만 쓰이고 값은 MySQL 이 찍는다(§90).
        LocalDateTime admittedAt = e.admittedAt() == null
                ? null
                : LocalDateTime.ofInstant(e.admittedAt(), ZoneOffset.UTC);

        return Token.transition(TokenEventType.from(e.eventType()).targetStatus(),
                e.tokenId(), e.queueId(), e.tenantId(), e.userId(), e.seq(),
                issuedAt, e.admitToken(), admittedAt, e.expiredReason());
    }
}
