package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.User;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;

/** 서비스 단위 테스트용 엔티티/예외 팩토리. 기준 시각: 2026-10-06 12:00 KST. */
final class PerformanceFixtures {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);
    static final Clock CLOCK = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

    private PerformanceFixtures() {}

    static SessionTimePolicy timePolicy() {
        return new SessionTimePolicy(CLOCK);
    }

    /** TransactionTemplate용 — getTransaction은 null 상태를 돌려주고 commit/rollback은 아무것도 안 한다. */
    static PlatformTransactionManager noopTransactionManager() {
        return mock(PlatformTransactionManager.class);
    }

    static User user(long id, String nickname) {
        User user = User.create("u" + id + "@test.com", "encoded", nickname);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    static Performance performance(long id, String venueName, User registrant) {
        Performance performance = Performance.create(venueName, "공연 " + id,
                "https://tickets.interpark.com/goods/" + id, "interpark:" + id, registrant);
        ReflectionTestUtils.setField(performance, "id", id);
        return performance;
    }

    static PerformanceSession session(long id, Performance performance, LocalDateTime startsAt) {
        PerformanceSession session = PerformanceSession.create(performance, startsAt);
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    /** MySQL이 돌려주는 형태의 unique 위반 (제약명은 "테이블.제약명"). */
    static DataIntegrityViolationException uniqueViolation(String table, String constraint) {
        String qualified = table + "." + constraint;
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'x' for key '" + qualified + "'", "23000", 1062);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, "insert ...",
                        ConstraintViolationException.ConstraintKind.UNIQUE, qualified));
    }
}
