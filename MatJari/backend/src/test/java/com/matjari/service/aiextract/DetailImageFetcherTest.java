package com.matjari.service.aiextract;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 외부 인터넷은 쓰지 않는다. 주소 규칙은 Document 로, 내려받기는 루프백 HttpServer 와 완화 모드로 시험한다. */
class DetailImageFetcherTest {

    private static final String H = "https://cdnticket.melon.co.kr";
    private final AiProperties props = new AiProperties();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Document doc(String body) {
        return Jsoup.parse("<html><head>" + body.split("\\|\\|")[0] + "</head><body>"
                + (body.contains("||") ? body.split("\\|\\|")[1] : "") + "</body></html>", "https://ticket.melon.com/");
    }

    private static byte[] image(int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private void serve(String path, int status, String type, byte[] body, String location) {
        server.createContext(path, ex -> {
            if (type != null) {
                ex.getResponseHeaders().add("Content-Type", type);
            }
            if (location != null) {
                ex.getResponseHeaders().add("Location", location);
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        });
    }

    private String local(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    // ---- 주소 후보 ----

    @Test
    void candidateRulesInStrictMode() {
        DetailImageFetcher f = new DetailImageFetcher(props);
        Document d = doc("<meta property=\"og:image\" content=\"" + H + "/upload/product/poster.jpg\">||"
                + "<img src=\"" + H + "/upload/product/poster.jpg?x=1\">"          // 포스터와 같은 파일
                + "<img src=\"" + H + "/upload/product/detail1.jpg\">"
                + "<img data-src=\"//cdnticket.melon.co.kr/upload/product/detail2.png\">" // 프로토콜 상대
                + "<img src=\"" + H + "/upload/product/detail1.jpg\">"             // 중복
                + "<img src=\"" + H + "/upload/product/noimage.gif\">"
                + "<img src=\"" + H + "/upload/other/x.jpg\">"                     // 경로 아님
                + "<img src=\"http://cdnticket.melon.co.kr/upload/product/a.jpg\">" // http
                + "<img src=\"https://evil.example.com/upload/product/a.jpg\">"     // 호스트
                + "<img src=\"https://cdnticket.melon.co.kr.evil.com/upload/product/a.jpg\">"
                + "<img src=\"https://user:pw@cdnticket.melon.co.kr/upload/product/a.jpg\">"
                + "<img src=\"https://cdnticket.melon.co.kr:8443/upload/product/a.jpg\">"
                + "<img src=\"https://cdnticket.melon.co.kr/upload/product/a b.jpg\">"
                + "<img src=\"https://cdnticket.melon.co.kr/upload/product/\u0001a.jpg\">"
                + "<img src=\"https://cdnticket.melon.co.kr/upload/product/" + "a".repeat(2100) + ".jpg\">"
                + "<img src=\"/upload/product/relative.jpg\">");
        List<URI> uris = f.candidateUris(d);
        assertEquals(List.of(URI.create(H + "/upload/product/detail1.jpg"),
                URI.create(H + "/upload/product/detail2.png")), uris);
    }

    @Test
    void hostListIsConfigurableAndExact() {
        props.setImageHosts(List.of("img.example.test"));
        DetailImageFetcher f = new DetailImageFetcher(props);
        assertNull(f.validate(H + "/upload/product/a.jpg"));
        assertEquals(URI.create("https://img.example.test/upload/product/a.jpg"),
                f.validate("https://IMG.example.test/upload/product/a.jpg"));
    }

    @Test
    void unsupportedSiteReturnsEmpty() {
        DetailImageFetcher f = new DetailImageFetcher(props);
        Document d = doc("||<img src=\"" + H + "/upload/product/detail1.jpg\">");
        assertTrue(f.fetch("yes24", d).isEmpty());
        assertTrue(f.fetch("ticketlink", d).isEmpty());
    }

    // ---- 내려받기 ----

    @Test
    void keepsOnlyTallImagesAndCapsAtThree() throws IOException {
        DetailImageFetcher f = new DetailImageFetcher(props, true);
        serve("/upload/product/tall1.jpg", 200, "image/jpeg", image(700, 3000, "jpg"), null);
        serve("/upload/product/tall2.png", 200, "image/png", image(700, 2500, "png"), null);
        serve("/upload/product/short.jpg", 200, "image/jpeg", image(700, 1500, "jpg"), null);   // 2000 미만
        serve("/upload/product/squat.jpg", 200, "image/jpeg", image(1000, 1900, "jpg"), null);  // 2배 미만
        serve("/upload/product/tall3.jpg", 200, "image/jpeg", image(600, 2400, "jpg"), null);
        serve("/upload/product/tall4.jpg", 200, "image/jpeg", image(600, 2400, "jpg"), null);   // 후보 4개 밖
        Document d = doc("||"
                + "<img src=\"" + local("/upload/product/short.jpg") + "\">"
                + "<img src=\"" + local("/upload/product/tall1.jpg") + "\">"
                + "<img src=\"" + local("/upload/product/squat.jpg") + "\">"
                + "<img src=\"" + local("/upload/product/tall2.png") + "\">"
                + "<img src=\"" + local("/upload/product/tall3.jpg") + "\">"
                + "<img src=\"" + local("/upload/product/tall4.jpg") + "\">");
        List<DetailImageFetcher.DetailImage> r = f.fetch("melon", d);
        // 후보는 앞 4개(short, tall1, squat, tall2)만 내려받는다 -> 상세 이미지는 tall1, tall2
        assertEquals(2, r.size());
        assertEquals(700, r.get(0).width());
        assertEquals(3000, r.get(0).height());
        assertEquals(2500, r.get(1).height());
    }

    @Test
    void skipsRedirectsWrongTypesAndUndecodable() throws IOException {
        DetailImageFetcher f = new DetailImageFetcher(props, true);
        serve("/upload/product/redir.jpg", 302, "image/jpeg", new byte[0], local("/upload/product/real.jpg"));
        serve("/upload/product/real.jpg", 200, "image/jpeg", image(700, 3000, "jpg"), null);
        serve("/upload/product/html.jpg", 200, "text/html", image(700, 3000, "jpg"), null);
        serve("/upload/product/webp.jpg", 200, "image/webp", new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, null);
        serve("/upload/product/junk.jpg", 200, "image/jpeg", new byte[]{1, 2, 3, 4, 5}, null);
        assertNull(f.download(URI.create(local("/upload/product/redir.jpg"))));
        assertNull(f.download(URI.create(local("/upload/product/html.jpg"))));
        assertNull(f.download(URI.create(local("/upload/product/webp.jpg"))));
        assertNull(f.download(URI.create(local("/upload/product/junk.jpg"))));
        assertEquals(3000, f.download(URI.create(local("/upload/product/real.jpg"))).height());
    }

    @Test
    void oversizedImageIsSkipped() throws IOException {
        props.setMaxImageBytes(1000);
        DetailImageFetcher f = new DetailImageFetcher(props, true);
        serve("/upload/product/big.png", 200, "image/png", image(700, 3000, "png"), null);
        // 빈 PNG 라 압축되어 1000바이트 이하일 수 있으니 임의 바이트로 한도를 넘긴다
        byte[] noise = new byte[5000];
        new java.util.Random(1).nextBytes(noise);
        serve("/upload/product/noise.png", 200, "image/png", noise, null);
        assertNull(f.download(URI.create(local("/upload/product/noise.png"))));
    }

    @Test
    void strictModeRefusesNonAllowedHostWithoutRequesting() {
        DetailImageFetcher f = new DetailImageFetcher(props);
        assertNull(f.download(URI.create(local("/upload/product/a.jpg")))); // http·루프백·미허용 호스트
    }
}
