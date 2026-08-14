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
 * 시각 규약(UTC) 기동 검증.
 *
 * <p>이 프로젝트는 저장 시각을 전부 UTC로 통일했는데(DECISIONS §77), 그 보장이
 * <b>서로 다른 세 설정이 맞물려야</b> 성립한다.
 * <ol>
 *   <li>JVM 기본 TZ = UTC — {@code *Application.main()}의 {@code TimeZone.setDefault}</li>
 *   <li>JDBC {@code connectionTimeZone=UTC} — (1)과 같아야 값이 항등으로 저장된다</li>
 *   <li>JDBC {@code forceConnectionTimeZoneToSession=true} — 세션 {@code time_zone}까지 UTC</li>
 * </ol>
 *
 * <p><b>하나라도 어긋나면 예외도 로그도 없이 저장값만 9시간 밀린다.</b> 그리고 그 사실은
 * 한참 뒤 집계가 이상할 때에야 드러난다. 이 클래스는 그 조용한 실패를 <b>기동 실패로 바꾼다.</b>
 *
 * <p>막으려는 구체적 사고: 컨테이너 기본 {@code TZ}가 UTC가 아닌 이미지로 배포,
 * 새 profile yml에 JDBC 파라미터 누락, 누군가 {@code main()}의 한 줄 삭제.
 * 셋 다 테스트로는 안 잡힌다(테스트는 이 빈을 통과하는 컨텍스트를 안 띄울 수도 있다).
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
