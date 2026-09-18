package com.sonix.queue.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

/**
 * 이유: 스케줄 작업 전담 서버. 잡 셋 — TokenReclaimJob(10초) · ReconcileJob(5분) · BillingSnapshotJob(매일).
 * 문제: queue-consumer 와 한 프로세스에 두면 어느 쪽도 제대로 늘릴 수 없다.
 * 원인: 소비는 유입량에 비례해 늘려야 하고, 스케줄 작업은 늘릴수록 중복 실행 방지가 필요하다.
 * 해결: 모듈을 나눈다(§73 D20). 적재는 {@code queue-consumer} 가 맡는다.
 *
 * @author sonix
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
