package com.matjari.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 엔티티 생성/수정 시각(@CreatedDate/@LastModifiedDate)을 ClockConfig의 Clock(Asia/Seoul) 기준으로 채운다.
 * JVM 기본 TZ(컨테이너는 UTC)에 의존하지 않게 하려는 것 — 모든 LocalDateTime은 KST 벽시계 시각으로 저장한다.
 *
 * @SpringBootApplication이 아닌 별도 @Configuration에 두는 이유: @WebMvcTest 슬라이스는 이 클래스를
 * 스캔하지 않으므로 JPA 메타모델 없이도 슬라이스 테스트가 뜬다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = JpaAuditingConfig.DATE_TIME_PROVIDER)
public class JpaAuditingConfig {

    public static final String DATE_TIME_PROVIDER = "kstDateTimeProvider";

    @Bean(name = DATE_TIME_PROVIDER)
    public DateTimeProvider kstDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
