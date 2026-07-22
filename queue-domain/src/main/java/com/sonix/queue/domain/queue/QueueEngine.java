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
}
