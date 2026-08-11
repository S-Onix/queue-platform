package com.sonix.queue.domain.queue;

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
}
