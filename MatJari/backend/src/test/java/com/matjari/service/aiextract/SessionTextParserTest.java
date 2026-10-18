package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTextParserTest {

    private final SessionTextParser parser = new SessionTextParser();

    private static ExtractedSession s(int y, int mo, int d, int h, int mi) {
        return new ExtractedSession(LocalDate.of(y, mo, d), LocalTime.of(h, mi));
    }

    @Test
    void melonRangeWithWeekdayTimes() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 2026년 6월 19일(금) ~ 6월 21일(일) 금 오후 8시 / 토 오후 5시 / 일 오후 4시", null, null);
        assertEquals(List.of(s(2026, 6, 19, 20, 0), s(2026, 6, 20, 17, 0), s(2026, 6, 21, 16, 0)), r);
    }

    @Test
    void rangeSkipsDaysWithoutWeekdayTime() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 2026년 6월 19일(금) ~ 6월 21일(일) 금 오후 8시 / 일 오후 4시", null, null);
        assertEquals(List.of(s(2026, 6, 19, 20, 0), s(2026, 6, 21, 16, 0)), r);
    }

    @Test
    void melonSpacedSingleDate() {
        assertEquals(List.of(s(2025, 1, 18, 18, 0)),
                parser.parse("공연시간 2025 년 1 월 18 일 ( 토 ) 오후 6 시", null, null));
    }

    @Test
    void yes24ListOfDatesWithKst() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 안내 2026년 10월 9일(금) 오후 6시(KST) 2026년 10월 10일(토) 오후 5시(KST) 2026년 10월 11일(일) 오후 3시(KST)",
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 11));
        assertEquals(List.of(s(2026, 10, 9, 18, 0), s(2026, 10, 10, 17, 0), s(2026, 10, 11, 15, 0)), r);
    }

    @Test
    void sameDayMultipleTimesInheritMeridiem() {
        assertEquals(List.of(s(2026, 10, 3, 14, 0), s(2026, 10, 3, 19, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오후 2시, 7시", null, null));
    }

    @Test
    void ambiguousSecondTimeIsNotGuessed() {
        // 오전 11시 뒤의 1시는 오전/오후를 알 수 없다(같은 오전으로 보면 앞 시각보다 이르다)
        assertEquals(List.of(s(2026, 10, 3, 11, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오전 11시, 1시", null, null));
        // 24시간 표기가 확실하면 쓴다
        assertEquals(List.of(s(2026, 10, 3, 11, 0), s(2026, 10, 3, 19, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오전 11시, 19시", null, null));
        // 첫 시각이 오전/오후 없이 모호하면 버린다
        assertTrue(parser.parse("공연시간 2026년 10월 3일(토) 7시", null, null).isEmpty());
    }

    @Test
    void noonAndMidnight() {
        assertEquals(List.of(s(2026, 10, 3, 0, 0), s(2026, 10, 3, 12, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오전 12시 / 오후 12시", null, null));
    }

    @Test
    void colonFormat() {
        assertEquals(List.of(s(2026, 10, 3, 19, 30)),
                parser.parse("공연시간 2026년 10월 3일(토) 19:30", null, null));
    }

    @Test
    void notTenMinuteUnitIsDropped() {
        assertEquals(List.of(s(2026, 10, 3, 19, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오후 7시 5분 / 오후 7시", null, null));
    }

    @Test
    void outsidePeriodIsDropped() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 2026년 10월 9일(금) 오후 6시 2026년 10월 10일(토) 오후 5시",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12));
        assertEquals(List.of(s(2026, 10, 10, 17, 0)), r);
    }

    @Test
    void entranceRehearsalTicketOpenExcluded() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 2026년 10월 3일(토) 오후 7시\n입장 오후 6시\n리허설 오후 5시\n티켓오픈 오후 2시", null, null);
        assertEquals(List.of(s(2026, 10, 3, 19, 0)), r);
        // 같은 줄이라도 구분자(/)로 나뉜 다른 시각은 남긴다
        assertEquals(List.of(s(2026, 10, 3, 19, 0)),
                parser.parse("공연시간 2026년 10월 3일(토) 오후 7시 / 입장 오후 6시 30분", null, null));
        assertTrue(parser.parse("공연시간 2026년 10월 3일(토) 예매 오픈 오후 2시", null, null).isEmpty());
    }

    @Test
    void weekdayMismatchDateIsDropped() {
        // 2026-10-09 는 금요일인데 (토)로 적힌 경우
        assertTrue(parser.parse("공연시간 2026년 10월 9일(토) 오후 6시", null, null).isEmpty());
    }

    @Test
    void unreadableOrNoAnchorReturnsEmpty() {
        assertTrue(parser.parse(null, null, null).isEmpty());
        assertTrue(parser.parse("", null, null).isEmpty());
        assertTrue(parser.parse("공연시간 추후 공지", null, null).isEmpty());
        assertTrue(parser.parse("2026년 10월 9일(금) 오후 6시", null, null).isEmpty());
    }

    @Test
    void resultIsSortedDeduplicatedAndCapped() {
        List<ExtractedSession> r = parser.parse(
                "공연시간 2026년 10월 10일(토) 오후 5시 2026년 10월 9일(금) 오후 6시 2026년 10월 9일(금) 오후 6시", null, null);
        assertEquals(List.of(s(2026, 10, 9, 18, 0), s(2026, 10, 10, 17, 0)), r);
        // 범위가 100일을 넘으면 그 범위는 버린다
        assertTrue(parser.parse("공연시간 2026년 1월 1일 ~ 12월 31일 오후 7시", null, null).isEmpty());
        // 100개 이하 범위는 모두 읽고 상한은 100개
        List<ExtractedSession> hundred = parser.parse("공연시간 2026년 1월 1일 ~ 4월 10일 오후 7시", null, null);
        assertEquals(100, hundred.size());
    }

    @Test
    void multiLineRegionAfterAnchor() {
        List<ExtractedSession> r = parser.parse(
                "공연시간\n2026년 10월 3일(토) 오후 2시, 7시\n관람연령 만 7세 이상\n2026년 12월 1일(화) 오후 7시", null, null);
        assertEquals(List.of(s(2026, 10, 3, 14, 0), s(2026, 10, 3, 19, 0)), r);
    }
}
