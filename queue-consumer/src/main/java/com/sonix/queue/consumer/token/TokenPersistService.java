package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 토큰 적재의 트랜잭션 경계.
 *
 * <p>리스너와 분리한 이유는 두 가지다. 하나는 {@code @Transactional}이 프록시 기반이라
 * 같은 클래스 안에서 호출하면 걸리지 않기 때문이고, 다른 하나는 <b>커밋 시점을 리스너가
 * 관찰할 수 있어야</b> 하기 때문이다 — 이 메서드가 예외 없이 반환된 뒤에야 ack할 수 있다.
 *
 * <p>Redis·Kafka를 모르는 것도 의도적이다. 여기서는 포트({@link TokenRepository})만 보고,
 * 적재 수단이 바뀌어도 이 클래스는 그대로다.
 */
@Service
public class TokenPersistService {

    private final TokenRepository tokenRepository;

    public TokenPersistService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * 한 배치를 한 트랜잭션으로 적재한다.
     *
     * <p>배치 크기는 {@code max-poll-records}가 결정하며, hibernate {@code batch_size}와
     * 맞춰 두었다. 두 값이 어긋나면 JDBC 배치가 중간에 쪼개져 왕복이 늘어난다.
     *
     * <p><b>{@code ENQUEUED}만 다른 길로 간다.</b> 신규 적재는 충돌 시 no-op이면 되지만, 나머지
     * 전이는 <b>허용 출발 상태를 강제하는 가드</b>가 이벤트마다 달라 SQL이 갈린다 (§80 가드 표).
     *
     * @param tokens 같은 타입만 담긴 목록. 호출자가 두 방식 중 하나로 만든다 —
     *               ① 배치에 중복 {@code tokenId}가 없으면 <b>타입별로 모아</b> 한 번에,
     *               ② 있으면 <b>같은 타입이 연속하는 구간</b>씩. ②가 필요한 이유는 같은 토큰의
     *               {@code ADMITTED → COMPLETED} 순서가 뒤집히면 COMPLETED가 먼저 no-op이 되어
     * 🔧 <b>§91에서 갈렸다.</b> COMPLETED 가드가 {@code status IN (0, 1)}로 넓어져
     * <b>COMPLETED는 순서에 의존하지 않는다</b>. 이 규칙이 여전히 지키는 것은
     * {@code ADMITTED}·{@code EXPIRED}의 {@code status = 0} 출발 가드다.
     *               그 토큰이 영원히 완료되지 않기 때문이다. 중복이 없으면 뒤집힐 대상 자체가 없다.
     *               판정은 {@code TokenLifecycleConsumer.canGroupByType}에 있다.
     */
    @Transactional
    public void persist(TokenEventType type, List<Token> tokens) {
        apply(type, tokens);
    }

    /**
     * 여러 구간을 <b>받은 순서 그대로, 한 트랜잭션으로</b> 적재한다.
     *
     * <p>구간 분할 경로가 지켜야 하는 것은 <b>문장의 실행 순서</b>이지 트랜잭션 경계가 아니다.
     * 구간마다 트랜잭션을 열면 순서는 같은데 <b>커밋만 구간 수만큼</b> 늘어난다 — 부하 중
     * 실측(2026-09-14, 로컬 100rps)에서 500건 배치가 265~322개 트랜잭션으로 쪼개졌고,
     * 가득 찬 배치 67회가 예외 없이 그랬다. {@code max-poll-records}·{@code batch_size}를
     * 500으로 맞춰 둔 의미가 그 층에서 사라진다(실효 배치 <b>1.82건</b>).
     *
     * <p><b>실패하면 호출자가 구간별로 다시 태운다.</b> 격리(이분 탐색)는 시도마다 독립
     * 트랜잭션이어야 하는데, 그건 <b>실패 경로에서만</b> 필요한 성질이다. 적재가 멱등이라
     * (ODKU) 다시 태워도 결과가 같다 — 그룹 경로가 분할 경로로 되돌아가는 것과 같은 구조다.
     *
     * @param segments 도착 순서대로 정렬된 구간들. 순서를 바꾸면 {@code ADMITTED}·{@code EXPIRED}의
     *                 {@code status = 0} 출발 가드가 다른 결과를 낸다
     */
    @Transactional
    public void persistAll(List<Segment> segments) {
        for (Segment segment : segments) {
            apply(segment.type(), segment.tokens());
        }
    }

    private void apply(TokenEventType type, List<Token> tokens) {
        if (type == TokenEventType.ENQUEUED) {
            tokenRepository.saveAllIfAbsent(tokens);
        } else {
            tokenRepository.applyTransition(type, tokens);
        }
    }

    /**
     * 같은 타입이 연속하는 구간 하나.
     *
     * @param offset 이 구간이 배치에서 시작하는 위치. 격리 인덱스는 <b>배치 전체 기준</b>이어야 한다
     */
    public record Segment(TokenEventType type, List<Token> tokens, int offset) {
    }
}
