package com.matjari.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 없이 JPA 메타모델 + 리포지토리 쿼리(JPQL @Query, 파생 쿼리)를 검증한다.
 * Spring Data JPA는 리포지토리 생성 시 @Query를 파싱(em.createQuery)하고 파생 쿼리의 속성 경로를 확인하므로,
 * 컨텍스트가 뜨면 쿼리 문법·엔티티 속성명이 유효하다는 뜻이다.
 * Hibernate는 JDBC 메타데이터 접근을 끄고 MySQL 방언을 명시해 실제 연결 없이 부팅한다
 * (데이터소스 URL은 일부러 연결 불가 주소 — 연결을 시도하면 테스트가 실패해 드러난다).
 * 한계: 생성된 SQL이 MySQL에서 실행되는지는 검증하지 않는다 (실 DB 통합 확인 필요).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=false"
})
class RepositoryQueryValidationTest {

    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private PerformanceSessionRepository performanceSessionRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ExchangeRequestRepository exchangeRequestRepository;
    @Autowired
    private ExchangeWantRangeRepository exchangeWantRangeRepository;
    @Autowired
    private ExchangeWantSessionRepository exchangeWantSessionRepository;
    @Autowired
    private ExchangeMatchRepository exchangeMatchRepository;

    @Test
    void allRepositoryQueriesParse() {
        assertThat(performanceRepository).isNotNull();
        assertThat(performanceSessionRepository).isNotNull();
        assertThat(ticketRepository).isNotNull();
        assertThat(userRepository).isNotNull();
        assertThat(exchangeRequestRepository).isNotNull();
        assertThat(exchangeWantRangeRepository).isNotNull();
        assertThat(exchangeWantSessionRepository).isNotNull();
        assertThat(exchangeMatchRepository).isNotNull();
    }
}
