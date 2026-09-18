package com.sonix.queue.consumer.token;

import com.sonix.queue.domain.queue.Token;
import com.sonix.queue.domain.queue.TokenEventType;
import com.sonix.queue.domain.queue.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이유: Kafka 배치를 DB 에 적재하는 트랜잭션 경계.
 * 문제: 리스너와 같은 클래스에 두면 {@code @Transactional} 이 안 걸린다.
 * 원인: 프록시 기반이라 자기호출은 프록시를 거치지 않는다. 그리고 ack 는 커밋 뒤여야 한다.
 * 해결: 별도 빈으로 분리해 커밋 시점을 리스너가 관찰하게 한다(예외 없이 반환 = 커밋됨).
 *
 * @author sonix
 */
@Service
public class TokenPersistService {

    private final TokenRepository tokenRepository;

    public TokenPersistService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * 이유: 같은 타입만 담긴 목록을 한 문장(다중행)으로 적재한다.
     * 문제: {@code max-poll-records} 와 hibernate {@code batch_size} 가 어긋나면 JDBC 배치가 쪼개진다.
     * 해결: 둘을 같은 값으로 맞춘다. {@code ENQUEUED} 만 no-op UPSERT 라 경로가 갈린다(§80 가드 표).
     * 🔧 예전 주석의 "COMPLETED 가 먼저면 no-op 이라 영원히 완료 안 된다"는 **거짓이다** —
     *    §91 이 가드를 {@code status IN (0,1)} 로 넓혀 COMPLETED 는 **순서에 의존하지 않는다**.
     *
     * @author sonix
     * @param type   이 목록의 이벤트 타입
     * @param tokens 같은 타입만 담긴 목록. 판정은 {@code TokenLifecycleConsumer.canGroupByType}
     */
    @Transactional
    public void persist(TokenEventType type, List<Token> tokens) {
        apply(type, tokens);
    }

    /**
     * 이유: 구간들을 받은 순서 그대로 <b>한 트랜잭션</b>으로 적재한다.
     * 문제: 구간마다 트랜잭션을 열면 500건 배치가 265~322 커밋으로 쪼개졌다(실측 2026-09-14).
     * 원인: 지켜야 하는 것은 <b>문장의 실행 순서</b>이지 트랜잭션 경계가 아니었다.
     * 해결: 한 커밋으로 묶어 실효 배치 1.82 → 500. 드레인 132 → 1,400~1,800/s.
     * ⚠️ 순서를 바꾸지 마라 — ADMITTED·EXPIRED 의 {@code status=0} 출발 가드가 다른 결과를 낸다.
     *
     * @author sonix
     * @param segments 도착 순서대로 정렬된 구간들
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
