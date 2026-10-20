package com.matjari.service.aiextract;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 평가 전용: AI 응답을 입력(이미지 바이트·이름 목록) 해시로 폴더에 저장해 두고, 같은 입력이 다시 오면 저장본을 돌려준다.
 * 도형 찾기 결과가 같으면 번호 이미지도 같으므로, 후처리 규칙만 바꾼 재평가는 API 를 부르지 않는다.
 * 같은 입력을 여러 번 부르는 다수결(라벨 3회)은 호출 순번별로 따로 저장한다. cacheOnly 면 저장본이 없을 때 API 대신 예외를 던진다.
 */
class CachingDetailExtractor implements DetailExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DetailExtractor delegate;
    private final Path dir;
    private final boolean cacheOnly;
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger misses = new AtomicInteger();

    CachingDetailExtractor(DetailExtractor delegate, Path dir, boolean cacheOnly) throws IOException {
        this.delegate = delegate;
        this.dir = Files.createDirectories(dir);
        this.cacheOnly = cacheOnly;
    }

    /** 공연이 바뀔 때 호출 순번을 초기화한다(같은 입력의 다수결 순번이 실행마다 0부터 시작하게). */
    void resetCounters() {
        seen.clear();
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public FirstPass first(List<Part> parts) {
        Object[] key = parts.stream().map(p -> p.jpeg() != null ? (Object) p.jpeg() : p.text()).toArray();
        return cached("first", key, JSON.constructType(FirstPass.class), () -> delegate.first(parts));
    }

    @Override
    public LabelResult label(byte[] originalJpeg, byte[] numberedJpeg, byte[] tilesJpeg, List<String> names, int segmentCount) {
        return cached("label", new Object[]{originalJpeg, numberedJpeg, tilesJpeg, String.join("\n", names), String.valueOf(segmentCount)},
                JSON.constructType(LabelResult.class), () -> delegate.label(originalJpeg, numberedJpeg, tilesJpeg, names, segmentCount));
    }

    @Override
    public Map<Integer, String> readTiles(List<Integer> ids, List<byte[]> jpegs) {
        Object[] key = new Object[jpegs.size() + 1];
        key[0] = ids.toString();
        for (int i = 0; i < jpegs.size(); i++) {
            key[i + 1] = jpegs.get(i);
        }
        return cached("tiles", key, JSON.getTypeFactory().constructMapType(Map.class, Integer.class, String.class), () -> delegate.readTiles(ids, jpegs));
    }

    @Override
    public Map<Integer, String> readLegend(List<Integer> ids, List<byte[]> jpegs) {
        Object[] key = new Object[jpegs.size() + 1];
        key[0] = ids.toString();
        for (int i = 0; i < jpegs.size(); i++) {
            key[i + 1] = jpegs.get(i);
        }
        return cached("legend", key, JSON.getTypeFactory().constructMapType(Map.class, Integer.class, String.class), () -> delegate.readLegend(ids, jpegs));
    }

    @Override
    public StripRange locate(List<Part> strips) {
        return delegate.locate(strips);
    }

    @Override
    public List<RawZone> second(byte[] jpeg, List<String> knownNames) {
        return cached("second", new Object[]{jpeg, String.join("\n", knownNames)},
                JSON.getTypeFactory().constructCollectionType(List.class, RawZone.class), () -> delegate.second(jpeg, knownNames));
    }

    private <T> T cached(String kind, Object[] inputs, JavaType type, java.util.function.Supplier<T> call) {
        String hash = hash(kind, inputs);
        int n = seen.computeIfAbsent(hash, k -> new AtomicInteger()).getAndIncrement();
        Path f = dir.resolve(kind + "-" + hash + "-" + n + ".json");
        try {
            if (Files.exists(f)) {
                hits.incrementAndGet();
                return JSON.readValue(f.toFile(), type);
            }
            misses.incrementAndGet();
            if (cacheOnly) {
                throw new ExtractionFailedException("저장된 응답 없음(" + kind + ")");
            }
            T v = call.get();
            if (v != null) {
                JSON.writeValue(f.toFile(), v);
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String hash(String kind, Object[] inputs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(kind.getBytes(StandardCharsets.UTF_8));
            for (Object o : inputs) {
                md.update((byte) 0);
                md.update(o instanceof byte[] b ? b : String.valueOf(o).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
