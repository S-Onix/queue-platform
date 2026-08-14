package com.sonix.queue.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

/**
 * Kafka 이벤트 소비 전담 서버.
 *
 * <p>플랫폼이 발행하는 이벤트를 구독해 처리하는 모든 컨슈머가 여기 모인다. 첫 소비자는
 * 토큰 생명주기 → DB 적재({@code com.sonix.queue.consumer.token})이고, 이후 빌링·분석·
 * 테넌트 웹훅처럼 <b>쓰기 대상이 다른</b> 소비자가 각자의 컨슈머 그룹으로 추가된다.
 * 같은 대상을 나눠 쓰는 병렬화는 그룹이 아니라 파티션이 담당한다.
 *
 * <p><b>왜 queue-batch가 아니라 별도 모듈인가:</b> 소비는 유입량에 비례하는 작업이라
 * 파티션 수만큼 인스턴스를 늘려야 한다. 반면 배치의 스케줄 작업(TTL 만료 감지, 파티션
 * 정리)은 대수를 늘리면 오히려 중복 실행을 막을 장치가 필요해진다. 확장 방향이 반대인
 * 둘을 한 프로세스에 두면 어느 쪽도 제대로 늘릴 수 없다.
 *
 * <p><b>⚠️ {@code @EnableScheduling}을 붙이지 말 것.</b> {@code scanBasePackages}가
 * {@code com.sonix.queue} 전체라 {@code queue-infrastructure}의 빈이 모두 올라온다.
 * 여기에 스케줄링까지 켜면 이 서버가 outbox 드레인 같은 배치 작업을 함께 돌리게 되고,
 * 전환 기간에 Stream 드레인과 Kafka 적재가 같은 토큰을 동시에 쓰는 사고로 이어진다.
 * 스케줄링을 켜지 않으면 {@code @Scheduled} 메서드는 빈으로만 존재하고 실행되지 않는다.
 */
@SpringBootApplication(scanBasePackages = "com.sonix.queue")
public class QueueConsumerApplication {
    public static void main(String[] args) {
        // 저장 시각은 전부 UTC다. LocalDateTime.now()가 이 기본 TZ를 읽으므로 여기서 못 박는다.
        // JDBC의 connectionTimeZone=UTC 와 반드시 같아야 한다 — 어긋나면 저장값이 9시간 밀린다.
        // 로그 표시만 Asia/Seoul 이다(logging.pattern.dateformat). 상세: DECISIONS §77
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(QueueConsumerApplication.class, args);
    }
}
