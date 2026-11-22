package com.matjari.service;

import com.matjari.domain.ChatMessage;
import com.matjari.exception.MatJariException;
import com.matjari.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 채팅 사진 저장소 (V30, 서버 디스크). 좌석 인증 사진({@link SeatClaimImageStore})과 같은 방식이지만 클래스·폴더를 따로 쓴다:
 * {@code matjari.upload.dir} 아래 {@code chat/} 폴더에 소문자 UUID 이름(확장자 없음)으로만 저장하고, 클라이언트가 준 이름·경로는 쓰지 않는다.
 * (좌석 인증 정리 작업은 {@code chat/} 하위 폴더를 건드리지 않는다: UUID 형식이 아닌 이름·디렉터리는 무시한다.)
 * 저장되는 바이트는 {@link ChatImageProcessor} 가 다시 인코딩한 JPEG·PNG 뿐이고, 읽을 때 형식은 시작 바이트로 다시 판정한다.
 * 키가 UUID 형식이 아니면 어떤 파일 연산도 하지 않는다(경로 탈출 불가). 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class ChatImageStore {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** 읽은 사진: Content-Type(image/jpeg | image/png)과 바이트. */
    public record ImageData(String contentType, byte[] bytes) {}

    /** 보관 기간이 지나 지워진(또는 파일이 없는) 사진 -> 410 IMAGE_EXPIRED. */
    public static class ImageExpiredException extends MatJariException {
        public ImageExpiredException() {
            super("보관 기간이 지나 사진이 삭제되었어요.");
        }

        public String getCode() {
            return "IMAGE_EXPIRED";
        }
    }

    private final Path root;

    public ChatImageStore(@Value("${matjari.upload.dir:./data/seat-claims}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize().resolve("chat");
    }

    public static boolean isValidKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** 시작 바이트로 형식 판정(JPEG·PNG 만). */
    public static Optional<String> detectContentType(byte[] data) {
        if (data == null) {
            return Optional.empty();
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (data.length >= png.length) {
            for (int i = 0; i < png.length; i++) {
                if (data[i] != png[i]) {
                    return Optional.empty();
                }
            }
            return Optional.of("image/png");
        }
        return Optional.empty();
    }

    /** 새 UUID 이름으로 저장하고 그 키를 돌려준다. 폴더는 없으면 만든다. */
    public String store(byte[] data) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            Files.write(resolve(key), data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("사진을 저장하지 못했습니다.", e);
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
            throw new UncheckedIOException("사진을 읽지 못했습니다.", e);
        }
    }

    /** 파일 삭제. 이미 없어도 성공(true). 실패하면 false(원인 종류만 로그, 경로는 남기지 않는다). */
    public boolean delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Chat image delete failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 메시지의 사진을 읽는다(참여자·관리자 경로 공통, 접근 권한 확인은 호출자 몫).
     * 사진 메시지가 아니면 404, 지운 시각이 있거나 파일이 없으면 410 {@link ImageExpiredException}.
     */
    public ImageData load(ChatMessage message) {
        if (!message.hasImage() || !isValidKey(message.getImageKey())) {
            throw new NotFoundException("사진을 찾을 수 없습니다.");
        }
        if (message.isImageExpired()) {
            throw new ImageExpiredException();
        }
        byte[] bytes = read(message.getImageKey()).orElseThrow(ImageExpiredException::new);
        String type = detectContentType(bytes).orElseThrow(ImageExpiredException::new);
        return new ImageData(type, bytes);
    }

    /** chat/ 폴더에서 마지막 수정이 {@code olderThan} 보다 오래된 파일의 키를 최대 {@code limit}개. UUID 형식이 아닌 이름·하위 폴더·심볼릭 링크는 건드리지 않는다. */
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
                    attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(olderThan)) {
                    keys.add(name);
                    if (keys.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Chat image listing failed: {}", e.getClass().getSimpleName());
        }
        return keys;
    }

    private Path resolve(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("잘못된 사진 키입니다.");
        }
        Path path = root.resolve(key).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("잘못된 사진 키입니다.");
        }
        return path;
    }
}
