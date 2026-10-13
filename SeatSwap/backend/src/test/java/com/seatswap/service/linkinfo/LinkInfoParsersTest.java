package com.seatswap.service.linkinfo;

import com.seatswap.service.linkinfo.LinkInfoParsers.ParsedInfo;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 실측 구조(2026-10-12)를 줄인 작은 HTML fixture. 실제 페이지 통째 저장 없음. */
class LinkInfoParsersTest {

    private static ParsedInfo parse(String site, String html) {
        return LinkInfoParsers.parse(site, Jsoup.parse(html));
    }

    private static String ld(String json) {
        return "<html><head><script type=\"application/ld+json\">" + json + "</script></head><body></body></html>";
    }

    // ---- YES24 ----

    @Test
    void yes24_jsonLd_titleVenuePeriod() {
        ParsedInfo info = parse("yes24", ld("{\"@context\":\"https://schema.org\",\"@type\":\"Event\","
                + "\"name\":\"세종 &lt;콘서트&gt; 2026\",\"startDate\":\"2026-10-11\",\"endDate\":\"2026-10-13\","
                + "\"location\":{\"@type\":\"Place\",\"name\":\"세종대학교 대양홀\"}}"));
        assertEquals("세종 <콘서트> 2026", info.title());
        assertEquals("세종대학교 대양홀", info.venue());
        assertEquals(LocalDate.of(2026, 10, 11), info.periodStart());
        assertEquals(LocalDate.of(2026, 10, 13), info.periodEnd());
    }

    @Test
    void yes24_fallsBackToOgTagsWhenNoJsonLd() {
        ParsedInfo info = parse("yes24", "<html><head>"
                + "<meta property=\"og:title\" content=\"예스24 티켓 / 두아 리파 내한\">"
                + "<meta property=\"og:description\" content=\"소개 일시: 2026.10.11 ~ 2026.10.12 러닝 장소: 고척스카이돔 \">"
                + "</head></html>");
        assertEquals("두아 리파 내한", info.title());
        assertEquals("고척스카이돔", info.venue());
        assertEquals(LocalDate.of(2026, 10, 11), info.periodStart());
        assertEquals(LocalDate.of(2026, 10, 12), info.periodEnd());
    }

    @Test
    void jsonLd_arrayGraphAndTypeArray() {
        ParsedInfo inArray = parse("ticketlink", ld("[{\"@type\":\"Product\",\"name\":\"상품\"},"
                + "{\"@type\":[\"Thing\",\"MusicEvent\"],\"name\":\"배열 공연\",\"location\":[{\"name\":\"올림픽홀\"}]}]"));
        assertEquals("배열 공연", inArray.title());
        assertEquals("올림픽홀", inArray.venue());

        ParsedInfo inGraph = parse("ticketlink", ld("{\"@graph\":[{\"@type\":\"Event\",\"name\":\"그래프 공연\","
                + "\"startDate\":\"2026-11-01T19:00:00+09:00\"}]}"));
        assertEquals("그래프 공연", inGraph.title());
        assertEquals(LocalDate.of(2026, 11, 1), inGraph.periodStart());
        assertNull(inGraph.venue());
    }

    @Test
    void ticketlink_skipsBrokenJsonAndStripsOgSuffix() {
        ParsedInfo info = parse("ticketlink", "<html><head>"
                + "<script type=\"application/ld+json\">{broken json</script>"
                + "<meta property=\"og:title\" content=\"뮤지컬 위키드 | 티켓링크\"></head></html>");
        assertEquals("뮤지컬 위키드", info.title());
        assertNull(info.venue());
    }

    @Test
    void tooLongFieldsBecomeNullNotTruncated() {
        String longTitle = "가".repeat(201);
        String longVenue = "나".repeat(101);
        ParsedInfo info = parse("yes24", ld("{\"@type\":\"Event\",\"name\":\"" + longTitle
                + "\",\"location\":{\"name\":\"" + longVenue + "\"}}"));
        assertNull(info.title());
        assertNull(info.venue());
        ParsedInfo exact = parse("yes24", ld("{\"@type\":\"Event\",\"name\":\"" + "가".repeat(200) + "\"}"));
        assertEquals(200, exact.title().length());
    }

    @Test
    void tagsAndControlCharsAreRemovedAndWhitespaceCollapsed() {
        ParsedInfo info = parse("yes24", ld("{\"@type\":\"Event\",\"name\":\"<b>굵은</b>\\u0000  제목\\n\\t끝\"}"));
        assertEquals("굵은 제목 끝", info.title());
    }

    @Test
    void badOrReversedDatesBecomeNull() {
        ParsedInfo reversed = parse("yes24", ld("{\"@type\":\"Event\",\"name\":\"역순\","
                + "\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-11\"}"));
        assertNull(reversed.periodStart());
        assertNull(reversed.periodEnd());
        ParsedInfo bad = parse("yes24", ld("{\"@type\":\"Event\",\"name\":\"이상\","
                + "\"startDate\":\"2026-13-40\",\"endDate\":\"내일\"}"));
        assertNull(bad.periodStart());
        assertNull(bad.periodEnd());
    }

    // ---- 멜론 ----

    private static String melon(String venueDd, String period) {
        return "<html><head><meta property=\"og:title\" content=\"\"></head><body><div class=\"box_consert_txt\">"
                + "<p class=\"tit\"> 멜론 &amp; 콘서트 </p>"
                + "<dl class=\"info_left\"><dt class=\"tit_info\">공연기간</dt>"
                + "<dd class=\"txt_info\" id=\"periodInfo\">" + period + "</dd></dl>"
                + "<dl class=\"info_right\"><dt class=\"tit_info\">공연장</dt>"
                + "<dd class=\"txt_info\">" + venueDd + "</dd></dl></div></body></html>";
    }

    @Test
    void melon_titleVenuePeriodRange() {
        ParsedInfo info = parse("melon", melon(" 올림픽공원 KSPO DOME ", "2025.03.04 - 2025.03.10"));
        assertEquals("멜론 & 콘서트", info.title());
        assertEquals("올림픽공원 KSPO DOME", info.venue());
        assertEquals(LocalDate.of(2025, 3, 4), info.periodStart());
        assertEquals(LocalDate.of(2025, 3, 10), info.periodEnd());
    }

    @Test
    void melon_linkedVenueIgnoresMoreLinkText() {
        String dd = "<a href=\"javascript:;\" class=\"link\"><span class=\"place\">덕스(DUEX) 홍대 1관&nbsp;</span>"
                + "<span class=\"ico_more\">바로가기</span></a>";
        assertEquals("덕스(DUEX) 홍대 1관", parse("melon", melon(dd, "2025.03.04")).venue());
    }

    @Test
    void melon_emptyOrDashVenueAndSingleDate() {
        ParsedInfo empty = parse("melon", melon("  ", "2025.03.04"));
        assertEquals("멜론 & 콘서트", empty.title());
        assertNull(empty.venue());
        assertEquals(LocalDate.of(2025, 3, 4), empty.periodStart());
        assertEquals(LocalDate.of(2025, 3, 4), empty.periodEnd());
        assertNull(parse("melon", melon("-", "")).venue());
    }

    @Test
    void emptyPageGivesNoTitle() {
        assertNull(parse("melon", "<html></html>").title());
        assertNull(parse("yes24", "<html></html>").title());
        assertNull(parse("unknown", "<html></html>").title());
    }

    // ---- 포스터 주소 ----

    private static String og(String content) {
        return "<html><head><meta property=\"og:image\" content=\"" + content + "\"></head></html>";
    }

    @org.junit.jupiter.api.Test
    void poster_allowedHostsOnly() {
        assertEquals("https://cdnticket.melon.co.kr/resource/a.jpg",
                LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("https://cdnticket.melon.co.kr/resource/a.jpg"))));
        assertEquals("https://image.toast.com/aaaaab/ticketlink/x.jpg",
                LinkInfoParsers.posterUrl("ticketlink", Jsoup.parse(og("https://image.toast.com/aaaaab/ticketlink/x.jpg"))));
        // 프로토콜 상대 주소는 https 로 보정
        assertEquals("https://tkfile.yes24.com/upload/p.jpg",
                LinkInfoParsers.posterUrl("yes24", Jsoup.parse(og("//tkfile.yes24.com/upload/p.jpg"))));
    }

    @org.junit.jupiter.api.Test
    void poster_rejectsOtherHostsSchemesAndPlaceholders() {
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("https://evil.example.com/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("http://cdnticket.melon.co.kr/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("https://cdnticket.melon.co.kr.evil.com/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("https://user@cdnticket.melon.co.kr/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("https://cdnticket.melon.co.kr:8443/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse(og("javascript:alert(1)"))));
        assertNull(LinkInfoParsers.posterUrl("yes24", Jsoup.parse(og("//tkfile.yes24.com/upload/PerfBlog/_noimage.gif"))));
        // 다른 사이트의 허용 호스트도 쓰지 않는다
        assertNull(LinkInfoParsers.posterUrl("yes24", Jsoup.parse(og("https://image.toast.com/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("interpark", Jsoup.parse(og("https://cdnticket.melon.co.kr/a.jpg"))));
        assertNull(LinkInfoParsers.posterUrl("melon", Jsoup.parse("<html></html>")));
    }

    @org.junit.jupiter.api.Test
    void poster_fallsBackToJsonLdImage() {
        String html = ld("{\"@type\":\"Event\",\"name\":\"x\",\"image\":[\"https://image.toast.com/aaaaab/ticketlink/y.jpg\"]}");
        assertEquals("https://image.toast.com/aaaaab/ticketlink/y.jpg", LinkInfoParsers.posterUrl("ticketlink", Jsoup.parse(html)));
    }
}
