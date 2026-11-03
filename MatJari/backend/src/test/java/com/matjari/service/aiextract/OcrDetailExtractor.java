package com.matjari.service.aiextract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 평가 전용: Haiku 대신 무료 OCR(로컬 OCR 서버 ocr_server.py, RapidOCR PP-OCRv5 mobile, Apache 2.0)로 이름을 읽는다. 도형 분할과
 * 그 뒤 규칙(범례 색 점검·번호 계열·좌우대칭·다듬기 등)은 운영 코드 그대로 탄다. 회차는 읽지 않고, AI 좌표 방식(second)은 지원하지 않는다.
 */
class OcrDetailExtractor implements DetailExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final Map<String, LabelResult> labels = new LinkedHashMap<>();

    OcrDetailExtractor(String base) {
        this.base = base;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public FirstPass first(List<Part> parts) {
        byte[] img = parts.stream().filter(p -> p.jpeg() != null).findFirst().map(Part::jpeg).orElse(null);
        // 1차 이름은 지도에 실제 보이는 것만: 지도 전체 글자(범례·안내 글자 섞임) 대신 도형 안에서 읽힌 이름만 쓴다
        List<String> names = new ArrayList<>();
        if (img != null) {
            for (RawLabel l : label(img, null, null, List.of(), 0).zones()) {
                if (!names.contains(l.name())) {
                    names.add(l.name());
                }
            }
        }
        return new FirstPass(List.of(), names, new SeatMapLocation(img != null, 1, 1, 1));
    }

    /** 같은 이미지의 다수결 3번은 결과가 같으므로 한 번만 읽는다(도형 분할의 정적 점검 필드가 병렬로 섞이지 않게 동기화). */
    @Override
    public synchronized LabelResult label(byte[] originalJpeg, byte[] numberedJpeg, byte[] tilesJpeg, List<String> names, int segmentCount) {
        String key = sha(originalJpeg);
        LabelResult cached = labels.get(key);
        if (cached != null) {
            return cached;
        }
        ShapeSegmenter.Result seg = new ShapeSegmenter().segment(originalJpeg);
        List<List<List<int[]>>> shapes = new ArrayList<>();
        for (ShapeSegmenter.Segment s : seg.segments()) {
            shapes.add(List.of(s.pixels()));
        }
        JsonNode read = post("/label", Map.of("image", b64(originalJpeg), "shapes", shapes)).path("names");
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        List<Integer> nonSeat = new ArrayList<>();
        for (int i = 0; i < seg.segments().size(); i++) {
            String n = read.path(i).asText("");
            int id = seg.segments().get(i).id();
            if (n.isBlank()) {
                nonSeat.add(id);
            } else {
                byName.computeIfAbsent(n, k -> new ArrayList<>()).add(id);
            }
        }
        List<RawLabel> zones = new ArrayList<>();
        byName.forEach((n, ids) -> zones.add(new RawLabel(n, ids)));
        LabelResult r = new LabelResult(zones, nonSeat);
        labels.put(key, r);
        return r;
    }

    @Override
    public Map<Integer, String> readTiles(List<Integer> ids, List<byte[]> jpegs) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            out.put(ids.get(i), post("/tile", Map.of("image", b64(jpegs.get(i)))).path("name").asText(""));
        }
        return out;
    }

    @Override
    public Map<Integer, String> readLegend(List<Integer> ids, List<byte[]> jpegs) {
        return readTiles(ids, jpegs);
    }

    @Override
    public List<RawZone> second(byte[] jpeg, List<String> knownNames) {
        return List.of();
    }

    /** OCR 서버는 한 번에 하나만 처리한다(타일 병렬 요청이 대기열을 넘쳐 실패하지 않게 줄 세운다). */
    private synchronized JsonNode post(String path, Object body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new ExtractionFailedException("OCR 서버 응답 " + res.statusCode());
            }
            return JSON.readTree(res.body());
        } catch (ExtractionFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionFailedException("OCR 서버 호출 실패: " + e.getClass().getSimpleName());
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
