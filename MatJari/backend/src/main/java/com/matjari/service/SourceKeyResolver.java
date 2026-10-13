package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.exception.FieldValidationException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 티켓팅 링크(sourceUrl) → 공연 중복 판정 키(sourceKey) 계산 (FR-02).
 *
 * 원칙: 서로 다른 공연이 같은 키로 "합쳐지는" 것이 같은 공연을 "놓치는" 것보다 훨씬 나쁘다
 * (합쳐지면 다른 공연 등록이 409로 막힌다). 그래서 애매하면 키를 더 구체적으로 만든다.
 *
 * 1) 알려진 사이트({@link TicketingSite})에서 상품 ID가 나오면 "{site}:{productId}"
 * 2) 그 외 일반 정규화: "url:" + 호스트(소문자·IDN→ASCII, www. 제거, 기본 포트 제거) + 경로(끝 / 제거)
 *    + 정렬된 쿼리(utm_*, fbclid, gclid 제거) + 프래그먼트(있으면 "#..." 그대로 포함 —
 *    해시 라우팅 사이트(#/show/1, #!/...)는 프래그먼트가 공연을 구분하므로 버리면 합쳐진다).
 *    퍼센트 인코딩은 대문자 hex로 통일하고, 비예약 문자(A-Z a-z 0-9 - . _ ~)를 인코딩한 것은 풀어 쓴다.
 *    http/https는 같은 키.
 * 3) 결과가 500자(source_key 컬럼)를 넘으면 "url-sha256:{hex}"
 *
 * 파싱: java.net.URL로 관대하게 파싱한 뒤 각 구성요소를 재인코딩한다 — 브라우저 주소창에서 복사한
 * 한글·공백 포함 링크도 받아들인다(프론트는 통과·서버는 400인 불일치 감소). 국제화 도메인은 IDN.toASCII.
 *
 * 이 단계에서 서버는 URL을 요청하지 않는다 (형식 검증만).
 */
@Component
public class SourceKeyResolver {

    static final String FIELD = "sourceUrl";
    static final String INVALID_MESSAGE = "올바른 티켓팅 링크(http/https)가 아닙니다.";
    private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid");

    // RFC 3986: 경로/쿼리/프래그먼트에 그대로 둘 수 있는 ASCII (unreserved + sub-delims + ":" "@" "/" "?")
    private static final String UNRESERVED_EXTRA = "-._~";
    private static final String ALLOWED_RAW = "!$&'()*+,;=:@/?";

    public String resolve(String sourceUrl) {
        ParsedUrl url = parse(sourceUrl);

        Optional<TicketingSite> site = TicketingSite.byHost(url.host());
        if (site.isPresent()) {
            Optional<String> productId = site.get().extractProductId(url.host(), url.path(), url.decodedQuery());
            if (productId.isPresent()) {
                return site.get().key() + ":" + productId.get();
            }
        }
        return limitLength(genericKey(url));
    }

    /** 정규화된 구성요소. host: 소문자 ASCII(IDN 변환, 끝 점 제거, www. 유지). path/query/fragment: 재인코딩된 원문. */
    record ParsedUrl(String scheme, String host, int port, String path, String rawQuery, String fragment,
                     Map<String, String> decodedQuery) {}

    /** 형식 검증 + 정규화 파싱: http/https, 호스트 필수, userinfo(user:pass@) 금지, 2048자 이하. */
    ParsedUrl parse(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()
                || sourceUrl.strip().length() > Performance.SOURCE_URL_MAX_LENGTH) {
            throw invalid();
        }
        URL url;
        try {
            url = new URL(sourceUrl.strip());
        } catch (MalformedURLException e) {
            throw invalid();
        }
        String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalid();
        }
        if (url.getUserInfo() != null) {
            throw invalid();
        }
        String host = normalizeHost(url.getHost());
        String path = encodeComponent(url.getPath());
        String query = url.getQuery() == null ? null : encodeComponent(url.getQuery());
        String fragment = url.getRef() == null ? null : encodeComponent(url.getRef());

        // 재조립한 결과가 올바른 URI인지 최종 확인 (포트 범위 등)
        try {
            new URI(scheme + "://" + host + (url.getPort() == -1 ? "" : ":" + url.getPort()) + path
                    + (query == null ? "" : "?" + query) + (fragment == null ? "" : "#" + fragment));
        } catch (URISyntaxException e) {
            throw invalid();
        }
        if (url.getPort() > 65535) {
            throw invalid();
        }
        return new ParsedUrl(scheme, host, url.getPort(), path, query, fragment, decodeQuery(query));
    }

    private String genericKey(ParsedUrl url) {
        String host = url.host().startsWith("www.") ? url.host().substring(4) : url.host();
        StringBuilder sb = new StringBuilder("url:").append(host);

        int port = url.port();
        boolean defaultPort = port == -1
                || ("http".equals(url.scheme()) && port == 80)
                || ("https".equals(url.scheme()) && port == 443);
        if (!defaultPort) {
            sb.append(':').append(port);
        }

        String path = url.path();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        sb.append(path);

        String query = normalizedQuery(url.rawQuery());
        if (!query.isEmpty()) {
            sb.append('?').append(query);
        }
        if (url.fragment() != null && !url.fragment().isEmpty()) {
            sb.append('#').append(url.fragment());
        }
        return sb.toString();
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw invalid();
        }
        String h = host;
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[")) { // IPv6 리터럴
            return h.toLowerCase(Locale.ROOT);
        }
        try {
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        h = h.toLowerCase(Locale.ROOT);
        if (h.isEmpty() || !h.matches("[a-z0-9.-]+")) {
            throw invalid();
        }
        return h;
    }

    /**
     * 경로/쿼리/프래그먼트 재인코딩.
     * - 허용 ASCII는 그대로, 그 외(공백·한글 등)는 UTF-8 퍼센트 인코딩(대문자 hex)
     * - 기존 %xx는 대문자 hex로 통일, 비예약 문자를 인코딩한 것(%41 등)은 문자로 되돌림
     * - 뒤에 hex 두 자리가 없는 %는 %25로 인코딩
     */
    static String encodeComponent(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length() && isHex(raw.charAt(i + 1)) && isHex(raw.charAt(i + 2))) {
                int value = Integer.parseInt(raw.substring(i + 1, i + 3), 16);
                if (isUnreserved((char) value)) {
                    sb.append((char) value);
                } else {
                    sb.append('%').append(raw.substring(i + 1, i + 3).toUpperCase(Locale.ROOT));
                }
                i += 3;
                continue;
            }
            if (c < 0x80 && (isUnreserved(c) || ALLOWED_RAW.indexOf(c) >= 0)) {
                sb.append(c);
                i++;
                continue;
            }
            int cp = raw.codePointAt(i);
            for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || UNRESERVED_EXTRA.indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 재인코딩된 쿼리에서 추적 파라미터를 빼고 "k=v" 문자열 기준으로 정렬. */
    private static String normalizedQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String name = decode(pair.split("=", 2)[0]).toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name)) {
                continue;
            }
            kept.add(pair);
        }
        kept.sort(null);
        return String.join("&", kept);
    }

    private static Map<String, String> decodeQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            result.putIfAbsent(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String limitLength(String key) {
        if (key.length() <= Performance.SOURCE_KEY_MAX_LENGTH) {
            return key;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "url-sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static FieldValidationException invalid() {
        return new FieldValidationException(FIELD, INVALID_MESSAGE);
    }
}
