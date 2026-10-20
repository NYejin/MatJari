package com.matjari.service.aiextract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모델 비교 평가 전용 Gemini 호출부(운영 코드에는 넣지 않는다). AnthropicDetailExtractor 와 같은 시스템 지시·JSON 스키마를 써서 같은 질문을 보낸다.
 * 키는 환경변수 GEMINI_API_KEY. 무료 등급의 분당 제한(429)은 기다렸다 다시 시도한다. 토큰 수는 평가 도구가 모으도록 AnthropicDetailExtractor
 * 로거에 같은 형식으로 남긴다.
 */
class GeminiDetailExtractor implements DetailExtractor {

    private static final Logger log = LoggerFactory.getLogger(AnthropicDetailExtractor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    GeminiDetailExtractor(String model) {
        this.model = model;
    }

    @Override
    public boolean available() {
        String k = System.getenv("GEMINI_API_KEY");
        return k != null && !k.isBlank();
    }

    @Override
    public FirstPass first(List<Part> parts) {
        JsonNode root = call(AnthropicDetailExtractor.FIRST_SYSTEM, parts, AnthropicDetailExtractor.firstSchema(), "first");
        List<RawSession> sessions = new ArrayList<>();
        for (JsonNode n : root.path("sessions")) {
            sessions.add(new RawSession(n.path("date").asText(""), n.path("time").asText("")));
        }
        List<String> zones = new ArrayList<>();
        for (JsonNode n : root.path("zones")) {
            zones.add(n.asText(""));
        }
        JsonNode sm = root.path("seatMap");
        return new FirstPass(sessions, zones, new SeatMapLocation(sm.path("found").asBoolean(false), sm.path("imageIndex").asInt(0),
                sm.path("firstSlice").asInt(0), sm.path("lastSlice").asInt(0)));
    }

    @Override
    public StripRange locate(List<Part> strips) {
        JsonNode root = call(AnthropicDetailExtractor.LOCATE_SYSTEM, strips, AnthropicDetailExtractor.locateSchema(), "locate");
        if (!root.path("found").asBoolean(false) || !root.path("firstStrip").canConvertToInt() || !root.path("lastStrip").canConvertToInt()) {
            return null;
        }
        int f = Math.max(1, root.path("firstStrip").asInt()), l = Math.max(1, root.path("lastStrip").asInt());
        return new StripRange(Math.min(f, l), Math.max(f, l));
    }

    @Override
    public List<RawZone> second(byte[] jpeg, List<String> knownNames) {
        String names = knownNames.isEmpty() ? "(없음)" : String.join(", ", knownNames);
        JsonNode root = call(AnthropicDetailExtractor.SECOND_SYSTEM, List.of(Part.ofText("이름 목록: " + names), Part.ofImage(jpeg)),
                AnthropicDetailExtractor.secondSchema(), "second");
        List<RawZone> zones = new ArrayList<>();
        for (JsonNode z : root.path("zones")) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (JsonNode p : z.path("polygons")) {
                List<List<Integer>> pts = new ArrayList<>();
                for (JsonNode pt : p) {
                    List<Integer> xy = new ArrayList<>();
                    for (JsonNode v : pt) {
                        xy.add(v.isInt() || v.isLong() ? v.asInt() : Integer.MIN_VALUE);
                    }
                    pts.add(xy);
                }
                polys.add(pts);
            }
            zones.add(new RawZone(z.path("name").asText(""), polys));
        }
        return zones;
    }

    @Override
    public LabelResult label(byte[] originalJpeg, byte[] numberedJpeg, byte[] tilesJpeg, List<String> names, int segmentCount) {
        String list = names.isEmpty() ? "(없음)" : String.join(", ", names);
        JsonNode root = call(AnthropicDetailExtractor.LABEL_SYSTEM, List.of(Part.ofText("이름 목록(철자 참고용): " + list + " / 도형 번호는 1~" + segmentCount),
                Part.ofText("[원본]"), Part.ofImage(originalJpeg), Part.ofText("[번호 표시]"), Part.ofImage(numberedJpeg),
                Part.ofText("[도형별 확대 타일]"), Part.ofImage(tilesJpeg)), AnthropicDetailExtractor.labelSchema(), "label");
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        List<Integer> nonSeat = new ArrayList<>();
        for (JsonNode n : root.path("tiles")) {
            if (!n.path("id").canConvertToInt()) {
                continue;
            }
            String text = n.path("text").asText("").trim();
            if (text.isEmpty()) {
                nonSeat.add(n.path("id").asInt());
            } else {
                byName.computeIfAbsent(text, k -> new ArrayList<>()).add(n.path("id").asInt());
            }
        }
        List<RawLabel> out = new ArrayList<>();
        byName.forEach((k, v) -> out.add(new RawLabel(k, v)));
        return new LabelResult(out, nonSeat);
    }

    @Override
    public Map<Integer, String> readTiles(List<Integer> ids, List<byte[]> jpegs) {
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            parts.add(Part.ofText("[타일 id " + ids.get(i) + "]"));
            parts.add(Part.ofImage(jpegs.get(i)));
        }
        Map<String, Object> tile = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer"), "text", Map.of("type", "string")),
                "required", List.of("id", "text"));
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("tiles", Map.of("type", "array", "items", tile)), "required", List.of("tiles"));
        JsonNode root = call(AnthropicDetailExtractor.READ_SYSTEM, parts, schema, "label");
        Map<Integer, String> out = new LinkedHashMap<>();
        for (JsonNode n : root.path("tiles")) {
            if (n.path("id").canConvertToInt()) {
                out.put(n.path("id").asInt(), n.path("text").asText("").trim());
            }
        }
        return out;
    }

    private JsonNode call(String system, List<Part> parts, Map<String, Object> schema, String stage) {
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Part p : parts) {
            ps.add(p.text() != null ? Map.of("text", p.text())
                    : Map.of("inlineData", Map.of("mimeType", "image/jpeg", "data", Base64.getEncoder().encodeToString(p.jpeg()))));
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", ps)),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseJsonSchema", stripUnsupported(schema),
                        "maxOutputTokens", 16000));
        long started = System.nanoTime();
        try {
            String payload = JSON.writeValueAsString(body);
            for (int attempt = 0; ; attempt++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                        .timeout(Duration.ofSeconds(180)).header("content-type", "application/json")
                        .header("x-goog-api-key", System.getenv("GEMINI_API_KEY"))
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if ((res.statusCode() == 429 || res.statusCode() >= 500) && attempt < 8) {
                    Thread.sleep(15_000L * (attempt + 1));
                    continue;
                }
                if (res.statusCode() != 200) {
                    throw new ExtractionFailedException("Gemini " + res.statusCode() + " " + res.body().substring(0, Math.min(200, res.body().length())));
                }
                JsonNode r = JSON.readTree(res.body());
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : r.path("candidates").path(0).path("content").path("parts")) {
                    if (!part.path("thought").asBoolean(false)) {
                        sb.append(part.path("text").asText(""));
                    }
                }
                JsonNode u = r.path("usageMetadata");
                log.info("AI 호출 ({}): {}ms, inputTokens={}, outputTokens={}, blocks={}", stage, (System.nanoTime() - started) / 1_000_000,
                        u.path("promptTokenCount").asLong(0), u.path("candidatesTokenCount").asLong(0) + u.path("thoughtsTokenCount").asLong(0), parts.size());
                JsonNode root = JSON.readTree(sb.toString());
                if (root == null || !root.isObject()) {
                    throw new ExtractionFailedException("Gemini 응답 형식 오류");
                }
                return root;
            }
        } catch (ExtractionFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionFailedException("Gemini 호출 실패: " + e.getClass().getSimpleName());
        }
    }

    /** Gemini JSON 스키마가 받지 않는 키(additionalProperties)를 뺀다. */
    @SuppressWarnings("unchecked")
    private static Object stripUnsupported(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (!"additionalProperties".equals(k)) {
                    out.put((String) k, stripUnsupported(v));
                }
            });
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(stripUnsupported(v)));
            return out;
        }
        return node;
    }
}
