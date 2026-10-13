package com.matjari.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 없이 SQL 조립 규칙을 검증한다: 확장 조각 가드, COUNT 전용 쿼리의 조인 구성. */
class ExchangeCandidateSqlGuardTest {

    /** DB 없이 도는 안전장치 파싱 검증(ExchangeCandidateQueryTest 는 DB 가 없으면 통째로 건너뛰므로 여기에 둔다). */
    @Test
    void jdbcUrlParsingIgnoresQueryString() {
        assertThat(ExchangeCandidateQueryTest.parseHostAndDb("jdbc:mysql://localhost:13307/matjari_it?useSSL=false"))
                .containsExactly("localhost", "matjari_it");
        assertThat(ExchangeCandidateQueryTest.parseHostAndDb("jdbc:mysql://127.0.0.1/matjari_it"))
                .containsExactly("127.0.0.1", "matjari_it");
        // 쿼리스트링에 _it 가 있어도 DB 이름은 matjari 이다
        assertThat(ExchangeCandidateQueryTest.parseHostAndDb("jdbc:mysql://localhost:3306/matjari?x=_it"))
                .containsExactly("localhost", "matjari");
        assertThat(ExchangeCandidateQueryTest.parseHostAndDb("jdbc:mysql://db.example.com/a_it"))
                .containsExactly("db.example.com", "a_it");
        assertThat(ExchangeCandidateQueryTest.parseHostAndDb("mysql://localhost/a_it")).isNull();
    }

    @Test
    void v5ExclusionsAreOpenPairAndTicketLockOnly() {
        String fragment = ExchangeCandidateRepository.additionalExclusions();
        assertThat(fragment).startsWith("\n").endsWith("\n");
        assertThat(fragment.strip()).startsWith("AND");
        assertThat(fragment).doesNotContain("?").contains("exchange_match").contains("open_flag = 1")
                .contains("exchange_ticket_lock").contains("user_block");
        // 목록과 COUNT 양쪽에 같은 조각이 붙는다
        assertThat(ExchangeCandidateRepository.candidateSql()).contains(fragment.strip());
        assertThat(ExchangeCandidateRepository.countSql()).contains(fragment.strip());
    }

    @Test
    void emptyFragmentAddsNothing() {
        assertThat(ExchangeCandidateRepository.normalizeExclusions(null)).isEmpty();
        assertThat(ExchangeCandidateRepository.normalizeExclusions("  \n ")).isEmpty();
    }

    @Test
    void fragmentGetsLeadingAndTrailingNewlines() {
        String out = ExchangeCandidateRepository.normalizeExclusions("  AND NOT EXISTS (SELECT 1 FROM x WHERE x.id = tb.id)  ");
        assertThat(out).startsWith("\n").endsWith("\n").contains("AND NOT EXISTS");
        // 앞 SQL(마지막이 개행)과 뒤 ORDER BY 사이에 붙어도 토큰이 붙지 않는다
        assertThat(out.strip()).doesNotStartWith(" ");
    }

    @Test
    void fragmentMustStartWithAnd() {
        assertThatThrownBy(() -> ExchangeCandidateRepository.normalizeExclusions("NOT EXISTS (SELECT 1)"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExchangeCandidateRepository.normalizeExclusions("ANDROID = 1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExchangeCandidateRepository.normalizeExclusions("OR 1=1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fragmentMustNotContainBindPlaceholder() {
        assertThatThrownBy(() -> ExchangeCandidateRepository.normalizeExclusions("AND tb.id <> ?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("?");
    }

    @Test
    void countSqlOmitsUsersJoinButKeepsEveryPredicate() {
        String list = ExchangeCandidateRepository.candidateSql();
        String count = ExchangeCandidateRepository.countSql();
        assertThat(list).contains("JOIN users ub").contains("STRAIGHT_JOIN").contains("ORDER BY");
        assertThat(count).doesNotContain("users").doesNotContain("ORDER BY").startsWith("SELECT COUNT(*)");
        // 판정에 쓰이는 조인·조건은 목록과 같다
        for (String piece : new String[]{"psb.performance_id = psa.performance_id", "tb.active_flag = 1",
                "b.status = 'OPEN'", "wb.col_key  = ta.col_key", "psb.starts_at >= ?", "tb.user_id <> ta.user_id"}) {
            assertThat(count).contains(piece);
            assertThat(list).contains(piece);
        }
    }
}
