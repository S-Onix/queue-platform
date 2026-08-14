package com.sonix.queue.api;

import com.sonix.queue.api.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@SpringBootApplication(scanBasePackages = "com.sonix.queue")
@EnableConfigurationProperties(JwtProperties.class)
@EnableScheduling
public class QueueApiApplication {
    public static void main(String[] args) {
        // 저장 시각은 전부 UTC다. LocalDateTime.now()가 이 기본 TZ를 읽으므로 여기서 못 박는다.
        // JDBC의 connectionTimeZone=UTC 와 반드시 같아야 한다 — 어긋나면 저장값이 9시간 밀린다.
        // 로그 표시만 Asia/Seoul 이다(logging.pattern.dateformat). 상세: DECISIONS §77
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(QueueApiApplication.class, args);
    }
}