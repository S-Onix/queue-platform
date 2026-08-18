package com.sonix.queue.domain.queue;

import java.util.List;
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
