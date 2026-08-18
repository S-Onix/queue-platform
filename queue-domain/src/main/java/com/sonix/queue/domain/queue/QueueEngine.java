package com.sonix.queue.domain.queue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface QueueEngine {

    /**
     * 대기열 진입 관련 인터페이스
     * >> 추후 RedisQueueEngine을 통해 실제 구현 코드 작성
     *
     * 하이브리드로 진행 예정
     * 1초에 1000건 이하의 요청인 경우 >> lua script
     * 1초에 1000건 이상의 요청이 올 경우 >> bulk lua script 진행
     *
     * 이슈사항 : 1초에 1000건에 대한 기준을 polling까지 잡아야하는가? >> Polling은 push 방식으로 변경 진행하여 실제 admit이 발생할 때에만 ranking 계산한다.
     * */
    EnqueueResult enqueue(String queueId, String identifier);

    /**
     * 큐 스냅샷 조회 — 맨앞 seq(frontSeq)와 대기 인원(total).
     * 폴링 순위 계산의 공유값. Caffeine 캐시(WAS-local)가 이 결과를 2초간 재사용한다.
     */
    QueueSnapshot readSnapshot(String queueId);

    /**
     * 폴링 소유권 검증 + keepalive를 한 번에 수행. 쓰기(master).
     *
     * <p>seq에 해당하는 대기 항목이 있고, 그 항목에 발급된 tokenId가 인자와 일치할 때만 true.
     * seq는 큐별 INCR이라 추측이 자명하므로 seq 존재만으로 판정하면 남의 대기 항목을
     * 들여다보고 keepalive까지 걸 수 있다 — 검증과 갱신을 분리하지 말 것.
     *
     * @param keepalive true면 검증 통과 시 last-active를 nowMillis로 갱신
     * @return 검증 통과 여부
     */
    boolean verifyWaiting(String queueId, long seq, String tokenId, boolean keepalive, long nowMillis);

    /**
     * 대기열 앞에서 count명을 꺼내 admitToken을 발급한다. 쓰기(master), 전 구간 원자(§80).
     *
     * <p>같은 {@code requestId}로 다시 부르면 대기열을 건드리지 않고 저장된 결과를 그대로 돌려준다
     * ({@link AdmitResult#replay()}). Tenant의 재시도가 두 번 뽑아가는 것을 막는 유일한 장치다.
     *
     * <p>count 상한은 여기서 막지 않는다 — API DTO의 검증이 강제한다(FRS §6.4).
     *
     * @param requestId Tenant가 정하는 멱등 키. 큐 스코프로 저장된다.
     * @param nowMillis 현재 epoch ms(UTC). admitToken 만료 시각의 기준이며 <b>호출자가 넘긴다</b> —
     *                  Lua에서 시각을 만들면 스크립트가 비결정적이 된다.
     */
    AdmitResult admit(String queueId, String requestId, int count, long nowMillis);

    /**
     * identifier → issuedAt. {@code queue:&#123;q&#125;:tokens} Hash 값 {@code "tokenId|issuedAt"}의 뒷조각을 읽는다.
     *
     * <p>🔴 <b>이것은 우회다.</b> {@code admit.lua}가 HGET으로 그 값을 이미 읽고도 tokenId만 쓰고
     * issuedAt을 버리기 때문에 생겼다. ADMITTED Kafka 이벤트({@code EnqueueEvent})는 issuedAt이
     * 필수인데(컨슈머의 멱등 키가 {@code UNIQUE(token_id, issued_at)}이고 파티션 키도 issued_at이다)
     * {@code AdmitResult.AdmitRecord}에 그 값이 없다. 없는 채로 발행하면 컨슈머가 <b>같은 토큰의
     * 두 번째 행</b>을 만든다.
     *
     * <p>admit.lua가 issuedAt을 records에 실어 주면 <b>이 메서드는 통째로 사라진다.</b>
     * (admit.lua·QueueKeys·RedisQueueEngine.admit은 이번 작업의 수정 금지 대상이라 우회했다)
     *
     * <p>왕복은 admit 호출당 1회(HMGET)다 — 토큰당이 아니다.
     *
     * @return 값이 없거나 구분자가 없는 항목은 결과에서 빠진다(맵 크기 &lt; 입력 크기일 수 있다)
     */
    Map<String, Instant> findIssuedAt(String queueId, List<String> identifiers);

    /**
     * verify: admitToken → tokenId ({@code admit-by-admit} 조회). 없으면 빈 Optional → 호출자가 DB fallback.
     */
    Optional<String> findTokenIdByAdmitToken(String queueId, String admitToken);

    /**
     * complete: 대기열·admit 흔적 제거 (FRS §6.6 ②). 멱등 — 없는 키를 지워도 무해하다.
     *
     * <p>{@code admit-by-admit}은 TTL 말고 삭제 경로가 여기뿐이다. 지우지 않으면 완료된
     * admitToken으로 최대 60초간 verify가 계속 통과한다.
     *
     * @param seq {@code admitted} ZSet 멤버가 {@code "seq|identifier"}라 필요하다
     */
    void cleanupCompleted(String queueId, String identifier, String tokenId, String admitToken, long seq);
}
