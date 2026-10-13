package com.seatswap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 좌석 소유 인증 요청의 예매내역 사진 저장소 (서버 디스크). 웹에서 직접 서빙하지 않는 디렉터리(seatswap.upload.dir)에
 * 소문자 UUID 이름(확장자 없음)으로만 저장하며, 클라이언트가 준 파일명·경로는 쓰지 않는다.
 * 형식은 Content-Type 이 아니라 파일 시작 바이트(매직 넘버)로 판정한다: JPEG(FF D8 FF), PNG(89 50 4E 47 0D 0A 1A 0A), WebP(RIFF....WEBP).
 * 키가 UUID 형식이 아니면 어떤 파일 연산도 하지 않는다(경로 탈출 불가). 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class SeatClaimImageStore {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path root;

    public SeatClaimImageStore(@Value("${seatswap.upload.dir:./data/seat-claims}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** 매직 넘버로 형식 판정. 지원하지 않는 형식이면 빈 값. */
    public static Optional<String> detectContentType(byte[] data) {
        if (data == null) {
            return Optional.empty();
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (startsWith(data, 0, png)) {
            return Optional.of("image/png");
        }
        if (data.length >= 12 && startsWith(data, 0, new byte[]{'R', 'I', 'F', 'F'})
                && startsWith(data, 8, new byte[]{'W', 'E', 'B', 'P'})) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** 새 UUID 이름으로 저장하고 그 키를 돌려준다. 디렉터리는 없으면 만든다. */
    public String store(byte[] data) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            Files.write(resolve(key), data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("이미지를 저장하지 못했습니다.", e);
        }
        return key;
    }

    /** 저장된 바이트. 파일이 없으면 빈 값. */
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("이미지를 읽지 못했습니다.", e);
        }
    }

    /** 파일 삭제. 이미 없어도 성공(true). 실패하면 false(원인은 로그에만, 경로는 남기지 않는다). */
    public boolean delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Seat claim image delete failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 저장 디렉터리에서 마지막 수정이 {@code olderThan} 보다 오래된 파일의 키(UUID 이름)를 최대 {@code limit}개 돌려준다.
     * UUID 형식이 아닌 이름·하위 디렉터리·심볼릭 링크는 건드리지 않는다(정리 작업이 엉뚱한 파일을 지우지 않게). 디렉터리가 없으면 빈 목록.
     */
    public List<String> listKeysOlderThan(Instant olderThan, int limit) {
        List<String> keys = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return keys;
        }
        try (Stream<Path> files = Files.list(root)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (!isValidKey(name)) {
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;   // 그 사이 지워졌거나 읽을 수 없는 파일은 건너뛴다
                }
                if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(olderThan)) {
                    keys.add(name);
                    if (keys.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Seat claim image listing failed: {}", e.getClass().getSimpleName());
        }
        return keys;
    }

    private Path resolve(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("잘못된 이미지 키입니다.");
        }
        Path path = root.resolve(key).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("잘못된 이미지 키입니다.");
        }
        return path;
    }
}
