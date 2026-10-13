package com.matjari.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 실제 MySQL 통합 테스트 공통 도우미 (ExchangeCandidateQueryTest 와 같은 MATJARI_IT_* 환경변수 방식).
 * 환경변수가 없으면 건너뛰고(MATJARI_IT_REQUIRED=true 또는 CI 가 있으면 실패), 있으면 안전장치를 통과한 DB 에서만 동작한다:
 * 호스트가 localhost/127.0.0.1, DB 이름이 `_it` 로 끝남, 연결 직후 SELECT DATABASE() 도 `_it` 로 끝남. 개발 DB(3306 matjari)는 건드리지 않는다.
 */
public final class RealMysqlSupport {

    private RealMysqlSupport() {}

    public static String url() {
        return System.getenv("MATJARI_IT_JDBC_URL");
    }

    public static String user() {
        return System.getenv().getOrDefault("MATJARI_IT_USER", "root");
    }

    public static String password() {
        return System.getenv().getOrDefault("MATJARI_IT_PASSWORD", "");
    }

    public static boolean configured() {
        return url() != null && !url().isBlank();
    }

    private static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("MATJARI_IT_REQUIRED")) || System.getenv("CI") != null;
    }

    /** 안전장치 검사. 위반하면 건너뛰지 않고 실패한다. */
    public static void assertSafe() {
        Matcher m = Pattern.compile("^jdbc:mysql://([^/:?]+)(?::\\d+)?/([^/?]+)(?:\\?.*)?$").matcher(url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다(jdbc:mysql://localhost:port/name_it): " + url());
        }
        if (!(m.group(1).equals("localhost") || m.group(1).equals("127.0.0.1"))) {
            fail("안전장치: 호스트가 localhost/127.0.0.1 이 아닙니다: " + m.group(1));
        }
        if (!m.group(2).endsWith("_it")) {
            fail("안전장치: DB 이름이 _it 로 끝나야 합니다: " + m.group(2));
        }
        try (Connection c = DriverManager.getConnection(url(), user(), password());
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT DATABASE()")) {
            rs.next();
            String db = rs.getString(1);
            if (db == null || !db.endsWith("_it")) {
                fail("안전장치: SELECT DATABASE() 가 _it 로 끝나지 않습니다: " + db);
            }
        } catch (java.sql.SQLException e) {
            fail("안전장치: 연결 확인 실패: " + e.getMessage());
        }
    }

    /** 안전장치 통과 후 Flyway clean (이후 Spring 의 Flyway 가 V1~최신을 다시 적용한다). */
    public static void cleanDatabase() {
        assertSafe();
        Flyway.configure().dataSource(url(), user(), password()).cleanDisabled(false).load().clean();
    }

    /** 환경변수가 없으면 클래스 전체를 건너뛴다. required 모드(MATJARI_IT_REQUIRED / CI)에서는 실패로 바꾼다. */
    public static class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (configured()) {
                return ConditionEvaluationResult.enabled("MATJARI_IT_JDBC_URL 설정됨");
            }
            String message = "[SKIPPED " + context.getDisplayName() + "] MATJARI_IT_JDBC_URL 이 없어 실제 MySQL 테스트를 건너뜁니다(README 참고).";
            if (required()) {
                fail("[MATJARI_IT_REQUIRED/CI] " + message);
            }
            System.out.println(message);
            return ConditionEvaluationResult.disabled(message);
        }
    }
}
