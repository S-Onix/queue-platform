package com.sonix.queue.domain.queue;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Queue
 *  - enqueue 요청시 발급되는 토큰 정보
 *  - admit 요청시 발급되는 토큰 정보
 *
 * <p><b>⚠️ 이 객체의 시각은 전부 UTC다. 이 프로젝트에서 UTC인 것은 tokens뿐이다.</b>
 * 다른 도메인 모델은 {@code LocalDateTime.now()}를 써서 <b>시스템 기본 TZ(KST)</b>가 들어가고,
 * DDL에 {@code DEFAULT CURRENT_TIMESTAMP(3)}가 붙은 테이블도 세션 TZ(+09:00)를 따라 KST가 된다.
 * <b>목록을 "포함"이 아니라 "제외"로 읽어라</b> — tokens만 UTC이고 나머지는 전부 KST다.
 * 같은 DB에 두 규약이 공존하고, 둘 다 {@code DATETIME(3)}이라 타입으로는 구분되지 않는다.
 *
 * <p>따라서 이 클래스에서 지킬 것 둘:
 * <ol>
 *   <li><b>상태 전이 메서드에서 {@code LocalDateTime.now()}를 호출하지 마라.</b> 그건 KST다.
 *       시각은 <b>호출자에게서 주입받는다</b> — {@code issue()}가 이미 그렇게 돼 있다.
 *       도메인이 시계를 직접 읽으면 테스트에서 고정할 수도 없다.</li>
 *   <li>주입하는 쪽은 {@code LocalDateTime.ofInstant(instant, ZoneOffset.UTC)}로 변환한다
 *       ({@code TokenLifecycleConsumer.toToken()} 참조).</li>
 * </ol>
 *
 * <p><b>Sprint 7에서 추가될 {@code completedAt}/{@code cancelledAt}/{@code expiredAt}이
 * 이 규약의 실제 과녁이다.</b> DB 컬럼은 이미 있으나 전부 NULL이다. 여기에 KST가 들어가면
 * {@code AVG(TIMESTAMPDIFF(SECOND, issued_at, completed_at))} 류의 대기시간 지표가
 * <b>오류가 아니라 그럴듯한 숫자로</b> 일괄 +32,400초(9시간)가 된다. 데이터가 섞인 뒤에는
 * 어느 행이 UTC인지 구분할 방법이 없다.
 *
 * <p>배경과 대안 비교: {@code doc/DECISIONS.md}, DDL 주석: {@code doc/schema.sql}
 * */
@Getter
public class Token {

    Long id;
    String tokenId;
    String queueId;
    Long tenantId;
    String userId;
    long seq;
    TokenStatus status;
    Integer expiredReason;  // Expired일 경우에
    String admitToken;      // admit 단계에서 생성됨
    boolean redisSyncNeeded;// Redis와 데이터 싱크가 맞는지 확인 (Redis가 강제로 다운될 경우를 대비)
    LocalDateTime issuedAt;

    private Token() {

    }

    /**
     * enqueue-events 이벤트로부터 WAITING 토큰 발행 (Consumer가 사용).
     * issuedAt은 이벤트의 Instant를 UTC 기준 LocalDateTime으로 변환한 값을 넘겨받는다
     * (재시도 시에도 동일해야 UNIQUE(token_id, issued_at) 멱등 성립).
     */
    public static Token issue(String tokenId, String queueId, Long tenantId,
                              String userId, long seq, LocalDateTime issuedAt) {
        Token token = new Token();
        token.tokenId = tokenId;
        token.queueId = queueId;
        token.tenantId = tenantId;
        token.userId = userId;
        token.seq = seq;
        token.status = TokenStatus.WAITING;
        token.expiredReason = null;
        token.admitToken = null;
        token.redisSyncNeeded = false;
        token.issuedAt = issuedAt;
        return token;
    }

    public static Token reconstruct(Long id, String tokenId, String queueId, Long tenantId,
                                    String userId, long seq, TokenStatus status,
                                    Integer expiredReason, String admitToken,
                                    boolean redisSyncNeeded, LocalDateTime issuedAt) {
        Token token = new Token();
        token.id = id;
        token.tokenId = tokenId;
        token.queueId = queueId;
        token.tenantId = tenantId;
        token.userId = userId;
        token.seq = seq;
        token.status = status;
        token.expiredReason = expiredReason;
        token.admitToken = admitToken;
        token.redisSyncNeeded = redisSyncNeeded;
        token.issuedAt = issuedAt;
        return token;
    }

}
