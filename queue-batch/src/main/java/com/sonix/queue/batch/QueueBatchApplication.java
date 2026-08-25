package com.sonix.queue.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

/**
 * 스케줄 작업 전담 서버.
 *
 * <p>잡 3개가 돈다 — {@code TokenReclaimJob}(회수 3경로, 10초) ·
 * {@code ReconcileJob}(Redis↔DB 대사, 5분) · {@code BillingSnapshotJob}(과금 스냅샷, 매일).
 * enqueue 적재를 담당하던 outbox 드레인 스케줄러는 Kafka 전환으로 사라졌다
 * (적재는 {@code queue-consumer}가 맡는다).
 *
 * <p>모듈을 지우지 않고 남겨 둔 이유는 <b>확장 방향이 다르기</b> 때문이다. 소비는 유입량에
 * 비례해 인스턴스를 늘려야 하지만, 스케줄 작업은 늘릴수록 중복 실행을 막을 장치가 필요해진다.
 * 둘을 한 프로세스에 두면 어느 쪽도 제대로 늘릴 수 없다.
 */
@SpringBootApplication(scanBasePackages = "com.sonix.queue")
@EnableScheduling
public class QueueBatchApplication {
    public static void main(String[] args) {
        // 저장 시각은 전부 UTC다. LocalDateTime.now()가 이 기본 TZ를 읽으므로 여기서 못 박는다.
        // JDBC의 connectionTimeZone=UTC 와 반드시 같아야 한다 — 어긋나면 저장값이 9시간 밀린다.
        // 로그 표시만 Asia/Seoul 이다(logging.pattern.dateformat). 상세: DECISIONS §77
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(QueueBatchApplication.class, args);
    }
}
