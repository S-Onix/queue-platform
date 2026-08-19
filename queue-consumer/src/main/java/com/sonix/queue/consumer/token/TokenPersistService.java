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
     * @param tokens <b>같은 타입이 연속하는 구간</b>이어야 한다. 타입별로 모아 넘기면 같은 토큰의
     *               전이 순서가 뒤집힌다 — 파티션이 지켜준 순서를 여기서 깨는 셈이다
     */
    @Transactional
    public void persist(TokenEventType type, List<Token> tokens) {
        if (type == TokenEventType.ENQUEUED) {
            tokenRepository.saveAllIfAbsent(tokens);
        } else {
            tokenRepository.applyTransition(type, tokens);
        }
    }
}
