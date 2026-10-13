package com.matjari.service.linkinfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사이트별 공개 HTML 파서 (순수 함수: Document -> ParsedInfo). 2026-10-12 실측 구조 기준.
 * - YES24·티켓링크: JSON-LD Event(name, location.name, startDate/endDate), 폴백은 og 태그
 * - 멜론: JSON-LD 없음. .box_consert_txt p.tit(제목), 공연장 dd, #periodInfo(기간)
 * 세 사이트 모두 회차(시각)는 Ajax라 공개 HTML에 없으므로 읽지 않는다.
 *
 * 읽은 문자열은 텍스트로만 쓴다: 태그 제거·엔티티 해석(jsoup text), 제어문자 제거, 공백 정리.
 * 제목 200자·공연장 100자(코드 포인트)를 넘으면 잘라 쓰지 않고 해당 필드를 null로 둔다.
 * 날짜는 yyyy-MM-dd(JSON-LD는 앞 10자) 또는 yyyy.MM.dd만 인정하고, 시작>종료면 기간 전체를 버린다.
 */
final class LinkInfoParsers {

    static final int TITLE_MAX = 200;
    static final int VENUE_MAX = 100;

    record ParsedInfo(String title, String venue, LocalDate periodStart, LocalDate periodEnd) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DOT_DATE = Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern VENUE_IN_DESCRIPTION = Pattern.compile("장소\\s*:\\s*(.+)$");
    private static final Pattern PERIOD_IN_DESCRIPTION = Pattern.compile("일시\\s*:\\s*([0-9.]+)\\s*~\\s*([0-9.]+)");

    private LinkInfoParsers() {}

    /** site: yes24 / ticketlink / melon. 그 외는 빈 결과. */
    static ParsedInfo parse(String site, Document doc) {
        return switch (site) {
            case "yes24" -> parseJsonLdSite(doc, true);
            case "ticketlink" -> parseJsonLdSite(doc, false);
            case "melon" -> parseMelon(doc);
            default -> new ParsedInfo(null, null, null, null);
        };
    }

    // ---- YES24 / 티켓링크 ----

    private static ParsedInfo parseJsonLdSite(Document doc, boolean yes24) {
        JsonNode event = findEvent(doc);
        String title = null;
        String venue = null;
        LocalDate start = null;
        LocalDate end = null;
        if (event != null) {
            title = clean(text(event.get("name")), TITLE_MAX);
            venue = clean(locationName(event.get("location")), VENUE_MAX);
            start = isoDate(text(event.get("startDate")));
            end = isoDate(text(event.get("endDate")));
        }
        if (title == null) {
            title = clean(stripTitleDecoration(meta(doc, "og:title"), yes24), TITLE_MAX);
        }
        String description = meta(doc, "og:description");
        if (yes24 && description != null) {
            if (venue == null) {
                Matcher m = VENUE_IN_DESCRIPTION.matcher(clean(description, 1000) == null ? "" : clean(description, 1000));
                if (m.find()) {
                    venue = clean(m.group(1), VENUE_MAX);
                }
            }
            if (start == null && end == null) {
                Matcher m = PERIOD_IN_DESCRIPTION.matcher(description);
                if (m.find()) {
                    start = dotDate(m.group(1));
                    end = dotDate(m.group(2));
                }
            }
        }
        return build(title, venue, start, end);
    }

    private static String stripTitleDecoration(String raw, boolean yes24) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        s = s.replaceFirst("\\s*\\|\\s*티켓링크\\s*$", "");
        if (yes24) {
            s = s.replaceFirst("^예스24\\s*티켓\\s*/\\s*", "");
            s = s.replaceFirst("\\s*[/|-]\\s*예스24\\s*티켓\\s*$", "");
        }
        return s;
    }

    /** script[type=application/ld+json] 중 Event(배열·@graph·@type 배열·MusicEvent 등 허용) 첫 번째. 깨진 JSON은 건너뛴다. */
    private static JsonNode findEvent(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = JSON.readTree(script.data());
            } catch (Exception e) {
                continue;
            }
            List<JsonNode> candidates = new ArrayList<>();
            collect(root, candidates, 0);
            for (JsonNode node : candidates) {
                if (isEvent(node) && node.hasNonNull("name")) {
                    return node;
                }
            }
        }
        return null;
    }

    private static void collect(JsonNode node, List<JsonNode> out, int depth) {
        if (node == null || depth > 3) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out, depth + 1);
            }
        } else if (node.isObject()) {
            out.add(node);
            collect(node.get("@graph"), out, depth + 1);
        }
    }

    private static boolean isEvent(JsonNode node) {
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && t.asText().endsWith("Event")) {
                    return true;
                }
            }
            return false;
        }
        return type.isTextual() && type.asText().endsWith("Event");
    }

    private static String locationName(JsonNode location) {
        if (location == null) {
            return null;
        }
        if (location.isArray()) {
            for (JsonNode item : location) {
                String name = locationName(item);
                if (name != null) {
                    return name;
                }
            }
            return null;
        }
        if (location.isTextual()) {
            return location.asText();
        }
        return location.isObject() ? text(location.get("name")) : null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String meta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        return el == null ? null : el.attr("content");
    }

    // ---- 멜론 ----

    private static ParsedInfo parseMelon(Document doc) {
        Element titleEl = doc.selectFirst(".box_consert_txt p.tit");
        String title = titleEl == null ? null : clean(titleEl.text(), TITLE_MAX);

        String venue = null;
        for (Element dt : doc.select("dt.tit_info")) {
            if ("공연장".equals(dt.text().strip())) {
                Element dd = dt.nextElementSibling();
                // 공연장이 링크로 감싸진 페이지는 .place 안에 이름만 있고 ".ico_more"(바로가기) 문구가 형제로 붙는다
                Element place = dd == null ? null : dd.selectFirst(".place");
                String v = dd == null ? null : clean((place != null ? place : dd).text(), VENUE_MAX);
                venue = "-".equals(v) ? null : v;
                break;
            }
        }

        LocalDate start = null;
        LocalDate end = null;
        Element period = doc.selectFirst("#periodInfo");
        if (period != null) {
            List<LocalDate> dates = new ArrayList<>();
            Matcher m = DOT_DATE.matcher(period.text());
            while (m.find() && dates.size() < 2) {
                LocalDate d = dotDate(m.group());
                if (d == null) {
                    dates.clear();
                    break;
                }
                dates.add(d);
            }
            if (!dates.isEmpty()) {
                start = dates.get(0);
                end = dates.size() > 1 ? dates.get(1) : dates.get(0);
            }
        }
        return build(title, venue, start, end);
    }

    // ---- 포스터 이미지 주소 (문자열만 읽는다. 서버는 이미지를 요청하지 않는다) ----

    static final int POSTER_URL_MAX = 2048;
    /** 사이트별 이미지 호스트 허용 목록(2026-10-13 실측: YES24 tkfile.yes24.com, 티켓링크 image.toast.com, 멜론 cdnticket.melon.co.kr). */
    private static final java.util.Map<String, java.util.Set<String>> POSTER_HOSTS = java.util.Map.of(
            "yes24", java.util.Set.of("tkfile.yes24.com"),
            "ticketlink", java.util.Set.of("image.toast.com"),
            "melon", java.util.Set.of("cdnticket.melon.co.kr"));

    /** og:image, 없으면 JSON-LD Event.image 첫 값. 허용 호스트의 https 이미지가 아니거나 'noimage' 자리표시 이미지면 null. */
    static String posterUrl(String site, Document doc) {
        java.util.Set<String> hosts = POSTER_HOSTS.get(site);
        if (hosts == null) {
            return null;
        }
        String url = normalizePoster(meta(doc, "og:image"), hosts);
        if (url == null) {
            JsonNode event = findEvent(doc);
            JsonNode image = event == null ? null : event.get("image");
            if (image != null && image.isArray() && !image.isEmpty()) {
                image = image.get(0);
            }
            url = normalizePoster(text(image), hosts);
        }
        return url;
    }

    private static String normalizePoster(String raw, java.util.Set<String> hosts) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.startsWith("//")) {
            s = "https:" + s;
        }
        if (s.isEmpty() || s.length() > POSTER_URL_MAX || !s.matches("\\A[\\x21-\\x7E]+\\z")) {
            return null; // 공백·제어·비ASCII 문자가 든 주소는 쓰지 않는다
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(s);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getHost() == null || !hosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                || uri.getRawPath() == null || uri.getRawPath().toLowerCase(java.util.Locale.ROOT).contains("noimage")) {
            return null;
        }
        return s;
    }

    // ---- 공통 ----

    private static ParsedInfo build(String title, String venue, LocalDate start, LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) {
            start = null;
            end = null;
        }
        return new ParsedInfo(title, venue, start, end);
    }

    /** 태그 제거·엔티티 해석 -> 제어/서식 문자 제거 -> 공백 연속 하나로 -> 비면 null -> 최대 길이 초과면 null(자르지 않음). */
    static String clean(String raw, int maxCodePoints) {
        if (raw == null) {
            return null;
        }
        String s = Jsoup.parseBodyFragment(raw).text();
        s = s.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > maxCodePoints) {
            return null;
        }
        return s;
    }

    private static LocalDate isoDate(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = ISO_DATE.matcher(raw.strip());
        return m.find() ? toDate(m.group(1), m.group(2), m.group(3)) : null;
    }

    private static LocalDate dotDate(String raw) {
        Matcher m = DOT_DATE.matcher(raw.strip());
        return m.matches() ? toDate(m.group(1), m.group(2), m.group(3)) : null;
    }

    private static LocalDate toDate(String y, String mo, String d) {
        try {
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
