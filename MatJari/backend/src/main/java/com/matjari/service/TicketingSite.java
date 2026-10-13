package com.matjari.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품 ID를 뽑아 sourceKey를 "{site}:{productId}"로 만들 수 있는 티켓팅 사이트 목록.
 * 같은 공연을 가리키는 링크가 경로·쿼리·모바일 여부만 달라도 하나의 공연으로 판정하기 위함이다.
 *
 * 범위 원칙: 호스트는 티켓 서비스 호스트를 정확히 나열하고(접미사 일치 금지), 경로는 처음부터 끝까지
 * 앵커를 건다. 패턴 밖의 링크는 일반 URL 정규화로 폴백한다 — 서로 다른 공연이 합쳐지는 것보다
 * 같은 공연을 놓치는 쪽이 낫다.
 *
 * 패턴 근거 (확신도 표기):
 * - [확인] = 실제 공개 링크 형식으로 알려진 것
 * - [추정] = 형식이 알려진 것과 유사해 포함했으나 실제 링크로 확인하지 못한 것
 * 같은 사이트 안에서 상품 ID 체계가 다를 수 있는 경로(예: YES24 /Special/{id})는 넣지 않았다.
 */
enum TicketingSite {

    /**
     * 인터파크 티켓 (2025 NOL 티켓 리브랜딩 포함).
     * [확인] https://tickets.interpark.com/goods/24013928
     * [확인] http://ticket.interpark.com/Ticket/Goods/GoodsInfo.asp?GoodsCode=24013928 (구 형식 — 이 경로일 때만 GoodsCode 인정)
     * [추정] https://mobileticket.interpark.com/goods/24013928
     * [추정] https://nol.interpark.com/ticket/goods/24013928 (nol. 호스트는 /ticket/ 접두 경로만)
     */
    INTERPARK("interpark",
            Set.of("tickets.interpark.com", "ticket.interpark.com", "mobileticket.interpark.com",
                    "nol.interpark.com")) {
        private final Pattern goodsPath = Pattern.compile("^/goods/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);
        private final Pattern nolGoodsPath =
                Pattern.compile("^/ticket/goods/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            if ("nol.interpark.com".equals(host)) {
                return group(nolGoodsPath, path);
            }
            Optional<String> fromPath = group(goodsPath, path);
            if (fromPath.isPresent()) {
                return fromPath;
            }
            if ("/Ticket/Goods/GoodsInfo.asp".equalsIgnoreCase(path)) {
                return numericParam(query, "GoodsCode");
            }
            return Optional.empty();
        }
    },

    /**
     * 멜론티켓.
     * [확인] https://ticket.melon.com/performance/index.htm?prodId=210813
     * 모바일(m.ticket.melon.com)은 경로를 확인하지 못해 제외 — 일반 정규화로 폴백.
     */
    MELON("melon", Set.of("ticket.melon.com")) {
        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            if ("/performance/index.htm".equalsIgnoreCase(path)) {
                return numericParam(query, "prodId");
            }
            return Optional.empty();
        }
    },

    /**
     * YES24 티켓.
     * [확인] http://ticket.yes24.com/Perf/49876
     * [확인] https://m.ticket.yes24.com/Perf/Detail/PerfInfo.aspx?IdPerf=49876 (모바일 — 이 경로일 때만 IdPerf 인정)
     * [확인] https://m.ticket.yes24.com/Perf/49876 (모바일 짧은 형식)
     */
    YES24("yes24", Set.of("ticket.yes24.com", "m.ticket.yes24.com")) {
        private final Pattern perfPath = Pattern.compile("^/Perf/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            Optional<String> fromPath = group(perfPath, path);
            if (fromPath.isPresent()) {
                return fromPath;
            }
            if ("/Perf/Detail/PerfInfo.aspx".equalsIgnoreCase(path)) {
                return numericParam(query, "IdPerf");
            }
            return Optional.empty();
        }
    },

    /**
     * 티켓링크.
     * [확인] https://www.ticketlink.co.kr/product/45678
     * [추정] https://m.ticketlink.co.kr/product/45678 (모바일)
     */
    TICKETLINK("ticketlink", Set.of("ticketlink.co.kr", "www.ticketlink.co.kr", "m.ticketlink.co.kr")) {
        private final Pattern productPath = Pattern.compile("^/product/(\\d{1,20})/?$", Pattern.CASE_INSENSITIVE);

        @Override
        Optional<String> extractProductId(String host, String path, Map<String, String> query) {
            return group(productPath, path);
        }
    };

    private final String key;
    private final Set<String> hosts;

    TicketingSite(String key, Set<String> hosts) {
        this.key = key;
        this.hosts = hosts;
    }

    String key() {
        return key;
    }

    /**
     * 상품 ID 추출 (숫자만, 앞자리 0 제거).
     * @param host 소문자 ASCII 호스트 (www. 유지)
     * @param path 재인코딩된 경로
     * @param query 디코딩된 쿼리 파라미터
     */
    abstract Optional<String> extractProductId(String host, String path, Map<String, String> query);

    static Optional<TicketingSite> byHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (TicketingSite site : values()) {
            if (site.hosts.contains(h)) {
                return Optional.of(site);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> group(Pattern pattern, String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = pattern.matcher(path);
        return m.matches() ? Optional.of(stripLeadingZeros(m.group(1))) : Optional.empty();
    }

    private static Optional<String> numericParam(Map<String, String> query, String name) {
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && entry.getValue().matches("\\d{1,20}")) {
                return Optional.of(stripLeadingZeros(entry.getValue()));
            }
        }
        return Optional.empty();
    }

    private static String stripLeadingZeros(String digits) {
        String stripped = digits.replaceFirst("^0+(?=\\d)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
