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
     * 대기열에 score=seq인 멤버가 있는지 확인 (존재=대기 항목 유효). 읽기.
     * ZCOUNT로 boolean만 판정 — 멤버(identifier)는 불필요.
     */
    boolean isWaiting(String queueId, long seq);

    /**
     * inactive_ttl 갱신 — last-active ZSet에 seq를 now(ms)로 기록. 쓰기(master).
     * 폴링 keepalive(ka=1 & 존재통과) 시에만 호출.
     */
    void touchLastActive(String queueId, long seq, long nowMillis);
}
