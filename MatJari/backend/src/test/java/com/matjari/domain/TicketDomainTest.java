package com.matjari.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketDomainTest {

    private final User owner = userWithId(1L);
    private final PerformanceSession session = PerformanceSession.create(
            Performance.create("KSPO DOME", "공연", "https://x", "x:1", owner), LocalDateTime.of(2026, 11, 1, 19, 0));

    private static User userWithId(long id) {
        User user = User.create("a@b.com", "encoded", "닉");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Ticket ticket() {
        return Ticket.create(owner, session, "1층 A", "1층A", "3열", "3", "5번", "5");
    }

    @Test
    void 생성하면_ACTIVE이고_보유자를_판정한다() {
        Ticket ticket = ticket();

        assertThat(ticket.isActive()).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(ticket.getZoneKey()).isEqualTo("1층A");
        assertThat(ticket.isOwnedBy(1L)).isTrue();
        assertThat(ticket.isOwnedBy(2L)).isFalse();
        assertThat(ticket.isOwnedBy(null)).isFalse();
    }

    @Test
    void 내리기는_멱등이다() {
        Ticket ticket = ticket();

        ticket.deactivate();
        ticket.deactivate();

        assertThat(ticket.isActive()).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.INACTIVE);
    }

    @Test
    void 필수값과_길이를_어기면_생성할_수_없다() {
        assertThatThrownBy(() -> Ticket.create(null, session, "A", "A", "1", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticket.create(owner, null, "A", "A", "1", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticket.create(owner, session, " ", "A", "1", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticket.create(owner, session, "A", "A".repeat(51), "1", "1", "1", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ticket.create(owner, session, "A", "A", "1", "1", "1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회차_등록_마감은_당일_끝_다음날_0시다() {
        assertThat(session.registrationDeadline()).isEqualTo(LocalDateTime.of(2026, 11, 2, 0, 0));
        assertThat(PerformanceSession.create(session.getPerformance(), LocalDateTime.of(2026, 11, 1, 23, 50))
                .registrationDeadline()).isEqualTo(LocalDateTime.of(2026, 11, 2, 0, 0));
    }

    @Test
    void markExchangedOnlyFromActive() {
        Ticket ticket = ticket();

        ticket.markExchanged();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.EXCHANGED);
        assertThat(ticket.isExchanged()).isTrue();
        assertThat(ticket.isActive()).isFalse();
        assertThatThrownBy(ticket::markExchanged).isInstanceOf(IllegalStateException.class);

        Ticket inactive = ticket();
        inactive.deactivate();
        assertThatThrownBy(inactive::markExchanged).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exchangedFromKeepsOwnerAndCopiesCounterpartSeat() {
        User other = userWithId(2L);
        PerformanceSession otherSession = PerformanceSession.create(session.getPerformance(), LocalDateTime.of(2026, 11, 2, 19, 0));
        Ticket theirs = Ticket.create(other, otherSession, "2층 B", "2층B", "7열", "7", "9번", "9");

        Ticket mineNew = Ticket.exchangedFrom(owner, theirs);

        assertThat(mineNew.getUser()).isSameAs(owner);
        assertThat(mineNew.getPerformanceSession()).isSameAs(otherSession);
        assertThat(mineNew.getZoneLabel()).isEqualTo("2층 B");
        assertThat(mineNew.getZoneKey()).isEqualTo("2층B");
        assertThat(mineNew.getRowLabel()).isEqualTo("7열");
        assertThat(mineNew.getRowKey()).isEqualTo("7");
        assertThat(mineNew.getColLabel()).isEqualTo("9번");
        assertThat(mineNew.getColKey()).isEqualTo("9");
        assertThat(mineNew.isActive()).isTrue();
        assertThat(mineNew.getId()).isNull();
        assertThatThrownBy(() -> Ticket.exchangedFrom(owner, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
