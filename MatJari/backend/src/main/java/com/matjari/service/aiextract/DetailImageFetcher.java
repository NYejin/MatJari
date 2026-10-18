package com.matjari.service.aiextract;

import com.matjari.service.linkinfo.PageFetcher;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 지원 사이트 상세 페이지 HTML에서 '상세 이미지'(세로로 아주 긴 이미지)를 찾아 내려받는다. 지금은 멜론만 지원하고
 * 다른 사이트는 빈 목록이다.
 *
 * 페이지는 호출자가 {@code PageFetcher}(고정 템플릿 URL)로 이미 받아 Document 로 넘긴다. 이 클래스는 사용자 URL을 요청하지 않고
 * 페이지에서 뽑은 이미지 주소만 요청하며, 그 주소는 https·허용 호스트 정확 일치·userinfo/포트/공백/제어·비ASCII 문자 없음·2048자 이하·
 * 경로에 /upload/product/ 가 있어야 하고 포스터(og:image와 같은 파일)·noimage 는 제외한다. 요청 때 모든 해석 주소가 공인 주소여야 하고
 * (PageFetcher.isBlockedAddress), 리다이렉트는 따라가지 않으며(3xx는 실패), 연결·읽기 제한과 크기 제한이 있다.
 * 이미지는 JDK ImageIO 가 읽을 수 있는 JPEG·PNG 만 쓰고(헤더로 크기만 읽는다, 전체 디코드는 슬라이서가 한다) 높이가 폭의 2배 이상이고
 * 높이 2000px 이상이면 상세 이미지로 남긴다. 한 장의 실패(네트워크·디코드 불가)는 그 장만 건너뛴다.
 * 이미지 요청은 순차이며 최악의 경우 후보 수 x 읽기 제한(15초)이 걸린다.
 */
@Component
public class DetailImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(DetailImageFetcher.class);
    private static final String USER_AGENT = "MatJariBot/0.1 (+portfolio project; contact: kidjin1016@gmail.com)";
    private static final String PRODUCT_PATH = "/upload/product/";
    private static final int MAX_URL_LENGTH = 2048;
    private static final Pattern URL_IN_HTML = Pattern.compile("(?<=[\"'(])https?://[^\\s\"'<>()&\\\\]+(?=[\"')])");
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-image-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** 내려받은 상세 이미지 한 장(원본 바이트 그대로). */
    public record DetailImage(String url, byte[] bytes, int width, int height) {
    }

    private final AiProperties properties;
    private final HttpClient client;
    /** 테스트 전용: true 면 https·호스트 허용 목록·포트·공인 IP 검사를 풀어 로컬 HttpServer 로 시험한다. 운영 빈은 항상 false. */
    private final boolean relaxedForTest;

    @Autowired
    public DetailImageFetcher(AiProperties properties) {
        this(properties, false);
    }

    DetailImageFetcher(AiProperties properties, boolean relaxedForTest) {
        this.properties = properties;
        this.relaxedForTest = relaxedForTest;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getImageConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param site SourceKeyResolver 키의 사이트 접두(예: "melon"). 멜론이 아니면 빈 목록.
     * @param page 그 사이트 상세 페이지(PageFetcher 결과)
     * @return 상세 이미지 최대 maxDetailImages 장(페이지 순서)
     */
    public List<DetailImage> fetch(String site, Document page) {
        if (!"melon".equals(site) || page == null) {
            return List.of();
        }
        List<URI> candidates = candidateUris(page);
        List<DetailImage> result = new ArrayList<>();
        int tried = 0;
        for (URI uri : candidates) {
            if (tried >= properties.getMaxCandidates() || result.size() >= properties.getMaxDetailImages()) {
                break;
            }
            tried++;
            DetailImage img = download(uri);
            if (img != null && isDetailShape(img.width(), img.height())) {
                result.add(img);
            }
        }
        return result;
    }

    boolean isDetailShape(int width, int height) {
        return width > 0 && height >= (long) width * 2 && height >= properties.getMinDetailHeight();
    }

    /** 이미지 주소 후보(순서 유지·중복 제거, 포스터·noimage 제외). 네트워크 요청 없음. */
    List<URI> candidateUris(Document page) {
        String posterKey = posterKey(page);
        Set<String> raws = new LinkedHashSet<>();
        for (Element el : page.select("img")) {
            for (String attr : new String[]{"src", "data-src", "data-original"}) {
                String v = el.attr(attr);
                if (!v.isEmpty()) {
                    raws.add(v);
                }
            }
        }
        Matcher m = URL_IN_HTML.matcher(page.outerHtml());
        while (m.find()) {
            raws.add(m.group());
        }
        List<URI> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : raws) {
            URI uri = validate(raw);
            if (uri == null) {
                continue;
            }
            String key = key(uri);
            if (key.equals(posterKey) || !seen.add(key)) {
                continue;
            }
            out.add(uri);
        }
        return out;
    }

    private String posterKey(Document page) {
        Element meta = page.selectFirst("meta[property=og:image]");
        if (meta == null) {
            return null;
        }
        String raw = meta.attr("content");
        URI uri = parseLoose(raw);
        return uri == null ? null : key(uri);
    }

    private static String key(URI uri) {
        return (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT)) + (uri.getPath() == null ? "" : uri.getPath());
    }

    private static URI parseLoose(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.startsWith("//") ? "https:" + raw : raw;
        try {
            return URI.create(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 규칙을 모두 통과한 주소만 돌려주고 아니면 null. */
    URI validate(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_URL_LENGTH) {
            return null;
        }
        String s = raw.startsWith("//") ? "https:" + raw : raw;
        if (s.length() > MAX_URL_LENGTH) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c >= 0x7f) { // 공백·제어·비ASCII
                return null;
            }
        }
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getUserInfo() != null) {
            return null;
        }
        if (!relaxedForTest) {
            if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || !properties.getImageHosts().contains(host)) {
                return null;
            }
        } else if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (!path.contains(PRODUCT_PATH) || lowerPath.contains("noimage")) {
            return null;
        }
        return uri;
    }

    /** 한 장을 안전하게 받아 크기를 확인한다. 어떤 이유로든 쓸 수 없으면 null. */
    DetailImage download(URI uri) {
        try {
            if (!relaxedForTest) {
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null
                        || !properties.getImageHosts().contains(host)) {
                    return null;
                }
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses.length == 0) {
                    return null;
                }
                for (InetAddress a : addresses) {
                    if (PageFetcher.isBlockedAddress(a)) {
                        return null;
                    }
                }
            }
            long readMs = properties.getImageReadTimeoutMs();
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readMs);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readMs))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/jpeg,image/png")
                    .header("Accept-Encoding", "identity")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return null; // 3xx 포함: 리다이렉트는 따라가지 않는다
                }
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (!type.startsWith("image/jpeg") && !type.startsWith("image/png") && !type.startsWith("image/pjpeg")) {
                    return null;
                }
                String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").trim();
                if (!encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) {
                    return null;
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > properties.getMaxImageBytes()) {
                    return null;
                }
                byte[] bytes = readBounded(body, deadlineNanos);
                if (bytes == null) {
                    return null;
                }
                int[] size = readSize(bytes);
                if (size == null) {
                    return null;
                }
                return new DetailImage(uri.toString(), bytes, size[0], size[1]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            log.debug("detail image skipped: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private byte[] readBounded(InputStream in, long deadlineNanos) throws IOException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }, remaining, TimeUnit.NANOSECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + (long) n > properties.getMaxImageBytes() || System.nanoTime() > deadlineNanos) {
                    return null;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            return null; // 데드라인에 스트림을 닫은 경우 포함
        } finally {
            watchdog.cancel(false);
        }
        return out.toByteArray();
    }

    /** ImageIO 헤더만 읽어 {폭, 높이}. 읽을 수 있는 리더가 없거나(WebP 등) 픽셀 수가 상한을 넘으면 null. */
    int[] readSize(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > properties.getMaxImagePixels()) {
                    return null;
                }
                return new int[]{w, h};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
