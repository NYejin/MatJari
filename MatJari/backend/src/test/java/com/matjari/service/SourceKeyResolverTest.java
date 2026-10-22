package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceKeyResolverTest {

    private final SourceKeyResolver resolver = new SourceKeyResolver();

    @ParameterizedTest
    @CsvSource({
            // 인터파크: 신/구/모바일/NOL 형식이 모두 같은 키
            "https://tickets.interpark.com/goods/24013928, interpark:24013928",
            "https://tickets.interpark.com/goods/24013928/?utm_source=x#info, interpark:24013928",
            "http://ticket.interpark.com/Ticket/Goods/GoodsInfo.asp?GoodsCode=24013928, interpark:24013928",
            "https://mobileticket.interpark.com/goods/24013928, interpark:24013928",
            "https://nol.interpark.com/ticket/goods/24013928, interpark:24013928",
            // 멜론 (데스크톱 상품 경로만)
            "https://ticket.melon.com/performance/index.htm?prodId=210813, melon:210813",
            "https://ticket.melon.com/performance/index.htm?prodId=0210813&utm_medium=x, melon:210813",
            // YES24
            "http://ticket.yes24.com/Perf/49876, yes24:49876",
            "https://ticket.yes24.com/perf/49876/, yes24:49876",
            "https://m.ticket.yes24.com/Perf/Detail/PerfInfo.aspx?IdPerf=49876, yes24:49876",
            "https://m.ticket.yes24.com/Perf/49876, yes24:49876",
            "https://ticket.yes24.com/Perf/Detail/PerfInfo.aspx?IdPerf=49876, yes24:49876",
            "https://ticket.yes24.com/Special/60595, yes24:60595",
            "https://ticket.yes24.com/New/Perf/Detail/DetailSpecial.aspx?IdPerf=60595, yes24:60595",
            // 티켓링크
            "https://www.ticketlink.co.kr/product/45678, ticketlink:45678",
            "https://ticketlink.co.kr/product/45678, ticketlink:45678",
            "https://m.ticketlink.co.kr/product/45678?utm_campaign=a, ticketlink:45678",
    })
    void knownSitesUseProductId(String url, String expected) {
        assertThat(resolver.resolve(url)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            // 호스트 범위 밖 (접미사 일치 금지)
            "https://www.interpark.com/goods/123, url:interpark.com/goods/123",
            "https://shop.interpark.com/goods/123, url:shop.interpark.com/goods/123",
            // 경로 앵커 밖
            "https://tickets.interpark.com/foo/goods/123, url:tickets.interpark.com/foo/goods/123",
            "https://tickets.interpark.com/goods/123/review, url:tickets.interpark.com/goods/123/review",
            "https://nol.interpark.com/goods/123, url:nol.interpark.com/goods/123",
            // GoodsCode는 구형 상품 경로에서만
            "https://ticket.interpark.com/other.asp?GoodsCode=123, url:ticket.interpark.com/other.asp?GoodsCode=123",
            // 멜론: 상품 경로가 아니거나 모바일 (경로 미확인) → 폴백
            "https://ticket.melon.com/search/index.htm?prodId=1, url:ticket.melon.com/search/index.htm?prodId=1",
            "https://m.ticket.melon.com/public/index.html?prodId=210813, url:m.ticket.melon.com/public/index.html?prodId=210813",
            // YES24 상품 경로 밖
            "https://ticket.yes24.com/Special/49876/Review, url:ticket.yes24.com/Special/49876/Review",
            "https://ticket.yes24.com/Perf/49876/Review, url:ticket.yes24.com/Perf/49876/Review",
            // 티켓링크 다른 경로
            "https://www.ticketlink.co.kr/sports/45678, url:ticketlink.co.kr/sports/45678",
    })
    void outsideKnownPatternsFallsBackToGenericKey(String url, String expected) {
        assertThat(resolver.resolve(url)).isEqualTo(expected);
    }

    @Test
    void genericNormalization() {
        String expected = "url:example.com/show/1?a=1&b=2";
        assertThat(resolver.resolve("https://www.Example.com/show/1/?b=2&a=1")).isEqualTo(expected);
        assertThat(resolver.resolve("http://example.com:80/show/1?utm_source=x&a=1&fbclid=z&b=2&gclid=q"))
                .isEqualTo(expected);
        assertThat(resolver.resolve("https://example.com:443/show/1?a=1&b=2")).isEqualTo(expected);
        // 기본 포트가 아니면 유지, 경로 대소문자는 유지
        assertThat(resolver.resolve("https://example.com:8443/Show/1")).isEqualTo("url:example.com:8443/Show/1");
        assertThat(resolver.resolve("https://example.com")).isEqualTo("url:example.com");
    }

    @Test
    void fragmentIsKeptSoHashRoutedShowsAreNotMerged() {
        String a = resolver.resolve("https://tickets.example.com/#/show/101");
        String b = resolver.resolve("https://tickets.example.com/#/show/202");
        assertThat(a).isEqualTo("url:tickets.example.com#/show/101");
        assertThat(a).isNotEqualTo(b);
        assertThat(resolver.resolve("https://tickets.example.com/#!/show/101"))
                .isEqualTo("url:tickets.example.com#!/show/101");
        // 일반 앵커도 유지한다 (합쳐짐보다 놓침이 낫다)
        assertThat(resolver.resolve("https://example.com/show/1#info")).isEqualTo("url:example.com/show/1#info");
        assertThat(resolver.resolve("https://example.com/show/1#")).isEqualTo("url:example.com/show/1");
    }

    @Test
    void percentEncodingIsNormalized() {
        // hex 대소문자 통일
        assertThat(resolver.resolve("https://example.com/%ea%b3%b5%ec%97%b0"))
                .isEqualTo(resolver.resolve("https://example.com/%EA%B3%B5%EC%97%B0"))
                .isEqualTo("url:example.com/%EA%B3%B5%EC%97%B0");
        // 비예약 문자를 인코딩한 것은 풀어 쓴다 (%41 = A, %7E = ~)
        assertThat(resolver.resolve("https://example.com/%41b%7Ec")).isEqualTo("url:example.com/Ab~c");
        // 예약 문자 인코딩(%2F)은 의미가 다르므로 유지
        assertThat(resolver.resolve("https://example.com/a%2Fb")).isEqualTo("url:example.com/a%2Fb");
    }

    @Test
    void rawNonAsciiSpacesAndIdnAreAccepted() {
        // 브라우저 주소창에서 복사한 한글/공백 링크 = 인코딩된 링크와 같은 키
        assertThat(resolver.resolve("https://example.com/공연 1?제목=두아"))
                .isEqualTo(resolver.resolve("https://example.com/%EA%B3%B5%EC%97%B0%201?%EC%A0%9C%EB%AA%A9=%EB%91%90%EC%95%84"));
        // 국제화 도메인 → punycode
        assertThat(resolver.resolve("https://예시.한국/show")).isEqualTo("url:xn--vv4b11d.xn--3e0b707e/show");
        assertThat(resolver.resolve("https://xn--vv4b11d.xn--3e0b707e/show")).isEqualTo("url:xn--vv4b11d.xn--3e0b707e/show");
        // 잘못된 % (뒤에 hex 두 자리 없음)는 %25로
        assertThat(resolver.resolve("https://example.com/100%")).isEqualTo("url:example.com/100%25");
    }

    @Test
    void overlongGenericKeyIsHashed() {
        String url = "https://example.com/" + "a".repeat(600);
        String key = resolver.resolve(url);
        assertThat(key).startsWith("url-sha256:").hasSize("url-sha256:".length() + 64);
        assertThat(resolver.resolve(url + "/")).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file", "javascript:alert(1)", "example.com/show", "https://",
            "https://user:pw@example.com/x", "not a url", "  ", "file:///etc/passwd",
            "https://exa mple.com/x", "https://example.com:99999/x", "mailto:a@b.com"
    })
    void rejectsNonHttpOrMalformed(String url) {
        assertThatThrownBy(() -> resolver.resolve(url))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("sourceUrl"));
    }

    @Test
    void rejectsNullAndTooLong() {
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> resolver.resolve("https://example.com/" + "a".repeat(2048)))
                .isInstanceOf(FieldValidationException.class);
    }
}
