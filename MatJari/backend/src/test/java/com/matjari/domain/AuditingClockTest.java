package com.matjari.domain;

import com.matjari.config.JpaAuditingConfig;
import jakarta.persistence.EntityListeners;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * createdAt/updatedAt이 JVM 기본 TZ가 아니라 Clock(Asia/Seoul) 기준으로 채워지는지 검증한다.
 * 실제 JpaAuditingConfig 배선(dateTimeProviderRef)과 엔티티의 @CreatedDate/@LastModifiedDate 매핑을
 * 그대로 쓰되, DB 연결 없이 부팅한다 (RepositoryQueryValidationTest와 같은 설정).
 * 기준 시각: 2026-10-06T03:00:00Z = 2026-10-06 12:00 KST.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, AuditingClockTest.FixedClockConfig.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false"
})
class AuditingClockTest {

    static final Instant INSTANT = Instant.parse("2026-10-06T03:00:00Z");
    static final LocalDateTime KST_NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(INSTANT, ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired
    private AuditingHandler auditingHandler;

    @Test
    void 생성시각은_Clock의_KST_기준으로_채워진다() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        Performance performance = Performance.create("KSPO DOME", "콘서트", "https://example.com/goods/1",
                "example:1", user);
        PerformanceSession session = PerformanceSession.create(performance, KST_NOW.plusDays(30));

        List.of(user, performance, session).forEach(auditingHandler::markCreated);

        assertThat(user.getCreatedAt()).isEqualTo(KST_NOW);
        assertThat(performance.getCreatedAt()).isEqualTo(KST_NOW);
        assertThat(performance.getUpdatedAt()).isEqualTo(KST_NOW);
        assertThat(session.getCreatedAt()).isEqualTo(KST_NOW);
    }

    @Test
    void 시각_필드가_있는_엔티티는_AuditingEntityListener를_등록한다() {
        for (Class<?> type : List.of(User.class, Performance.class, PerformanceSession.class)) {
            EntityListeners listeners = type.getAnnotation(EntityListeners.class);
            assertThat(listeners).as(type.getSimpleName()).isNotNull();
            assertThat(listeners.value()).as(type.getSimpleName()).contains(AuditingEntityListener.class);
        }
    }
}
