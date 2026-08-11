package com.sonix.queue.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄 작업 전담 서버.
 *
 * <p><b>현재 비어 있다.</b> enqueue 적재를 담당하던 outbox 드레인 스케줄러가 Kafka 전환으로
 * 사라졌고(적재는 {@code queue-consumer}가 맡는다), TTL 만료 감지·파티션 정리 등 실제
 * 스케줄 작업은 Sprint 7·9에서 들어온다.
 *
 * <p>모듈을 지우지 않고 남겨 둔 이유는 <b>확장 방향이 다르기</b> 때문이다. 소비는 유입량에
 * 비례해 인스턴스를 늘려야 하지만, 스케줄 작업은 늘릴수록 중복 실행을 막을 장치가 필요해진다.
 * 둘을 한 프로세스에 두면 어느 쪽도 제대로 늘릴 수 없다.
 */
@SpringBootApplication(scanBasePackages = "com.sonix.queue")
@EnableScheduling
public class QueueBatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueBatchApplication.class, args);
    }
}
