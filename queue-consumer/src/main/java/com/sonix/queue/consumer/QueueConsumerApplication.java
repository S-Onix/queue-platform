package com.sonix.queue.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

/**
 * 이유: Kafka 이벤트 소비 전담 서버. 첫 소비자는 토큰 생명주기 → DB 적재다.
 * 문제: queue-batch 와 합치면 어느 쪽도 제대로 늘릴 수 없다.
 * 원인: 소비는 파티션 수만큼 늘려야 하고, 스케줄 작업은 늘릴수록 중복 실행 방지가 필요하다.
 * 해결: 확장 방향이 반대라 모듈을 나눈다(§73 D20).
 * 🔴 {@code @EnableScheduling} 금지 — infra 의 {@code @Scheduled} 빈까지 돌아 이중 적재된다.
 *
 * @author sonix
 */
@SpringBootApplication(scanBasePackages = "com.sonix.queue")
public class QueueConsumerApplication {
    public static void main(String[] args) {
        // 이유: 저장 시각은 전부 UTC 다. LocalDateTime.now() 가 이 기본 TZ 를 읽는다.
        // 문제: JDBC 의 connectionTimeZone=UTC 와 어긋나면 저장값이 9시간 밀린다.
        // 해결: 여기서 못 박는다. 로그 표시만 Asia/Seoul 이다(§77).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(QueueConsumerApplication.class, args);
    }
}
