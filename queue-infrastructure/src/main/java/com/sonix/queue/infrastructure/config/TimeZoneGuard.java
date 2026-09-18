package com.sonix.queue.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 이유: 시각 규약(UTC) 기동 검증(§77).
 * 문제: 🔴 <b>하나라도 어긋나면 예외도 로그도 없이 저장값만 9시간 밀린다</b>(한참 뒤 집계에서 드러난다).
 * 원인: 보장이 <b>세 설정이 맞물려야</b> 성립한다 — JVM TZ · {@code connectionTimeZone} ·
 *       {@code forceConnectionTimeZoneToSession}. 사고: TZ 다른 이미지 · yml 누락 · {@code main()} 삭제.
 * 해결: 그 <b>조용한 실패를 기동 실패로 바꾼다</b>.
 *
 * @author sonix
 */
@Component
public class TimeZoneGuard {

    private static final Logger log = LoggerFactory.getLogger(TimeZoneGuard.class);

    private final DataSource dataSource;

    public TimeZoneGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void verify() {
        ZoneId jvm = ZoneId.systemDefault();
        if (!jvm.getRules().getOffset(java.time.Instant.now()).equals(ZoneOffset.UTC)) {
            throw new IllegalStateException(
                    "JVM 기본 TimeZone이 UTC가 아니다: " + jvm + ". "
                  + "저장 시각이 9시간 밀린다. TZ=UTC 또는 -Duser.timezone=UTC 로 기동하라. (DECISIONS §77)");
        }

        String sessionTz = readSessionTimeZone();
        if (!"+00:00".equals(sessionTz) && !"UTC".equalsIgnoreCase(sessionTz)) {
            throw new IllegalStateException(
                    "DB 세션 time_zone이 UTC가 아니다: " + sessionTz + ". "
                  + "JDBC URL에 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true 가 필요하다. "
                  + "(DECISIONS §77)");
        }

        log.info("시각 규약 확인: JVM={} / DB session time_zone={}", jvm, sessionTz);
    }

    private String readSessionTimeZone() {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT @@session.time_zone")) {
            return r.next() ? r.getString(1) : null;
        } catch (Exception e) {
            // DB에 못 붙는 것은 이 가드의 책임이 아니다. 다른 곳이 더 잘 보고한다.
            throw new IllegalStateException("시각 규약 검증 중 DB 조회 실패", e);
        }
    }
}
