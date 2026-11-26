package com.matjari.service;

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
import java.util.stream.Stream;

/**
 * 티켓·교환 희망 조건 사진 저장소 (V33, 서버 디스크). 채팅 사진({@link ChatImageStore})과 같은 방식에 폴더만 다르다:
 * {@code matjari.upload.dir} 아래 {@code listing/} 폴더에 소문자 UUID 이름(확장자 없음)으로만 저장하고 클라이언트가 준 이름·경로는 쓰지 않는다.
 * 저장되는 바이트는 {@link ChatImageProcessor} 가 다시 인코딩한 JPEG·PNG 뿐이다. 키가 UUID 형식이 아니면 어떤 파일 연산도 하지 않는다(경로 탈출 불가).
 * 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class ListingImageStore {

    private final Path root;

    public ListingImageStore(@Value("${matjari.upload.dir:./data/seat-claims}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize().resolve("listing");
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
            log.warn("Listing image delete failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 읽어서 형식을 시작 바이트로 다시 판정한다. 파일이 없거나 형식이 이상하면 {@link ChatImageStore.ImageExpiredException}. */
    public ChatImageStore.ImageData load(String key) {
        if (!ChatImageStore.isValidKey(key)) {
            throw new ChatImageStore.ImageExpiredException();
        }
        byte[] bytes = read(key).orElseThrow(ChatImageStore.ImageExpiredException::new);
        String type = ChatImageStore.detectContentType(bytes).orElseThrow(ChatImageStore.ImageExpiredException::new);
        return new ChatImageStore.ImageData(type, bytes);
    }

    /** listing/ 폴더에서 마지막 수정이 {@code olderThan} 보다 오래된 파일의 키를 최대 {@code limit}개. UUID 형식이 아닌 이름·하위 폴더·심볼릭 링크는 건드리지 않는다. */
    public List<String> listKeysOlderThan(Instant olderThan, int limit) {
        List<String> keys = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return keys;
        }
        try (Stream<Path> files = Files.list(root)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (!ChatImageStore.isValidKey(name)) {
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
            log.warn("Listing image listing failed: {}", e.getClass().getSimpleName());
        }
        return keys;
    }

    private Path resolve(String key) {
        if (!ChatImageStore.isValidKey(key)) {
            throw new IllegalArgumentException("잘못된 사진 키입니다.");
        }
        Path path = root.resolve(key).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("잘못된 사진 키입니다.");
        }
        return path;
    }
}
