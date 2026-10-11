package com.seatswap.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatClaimImageStoreTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void magicNumbersDecideTheFormat() {
        assertThat(SeatClaimImageStore.detectContentType(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0))).contains("image/jpeg");
        assertThat(SeatClaimImageStore.detectContentType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).contains("image/png");
        assertThat(SeatClaimImageStore.detectContentType(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V'))).contains("image/webp");
    }

    @Test
    void otherContentIsRejectedEvenIfItLooksLikeAnImageByName() {
        assertThat(SeatClaimImageStore.detectContentType(null)).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType(new byte[0])).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType("GIF89a".getBytes())).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes())).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType("<html><script>alert(1)</script>".getBytes())).isEmpty();
        // RIFF 이지만 WEBP 가 아님(WAV 등), PNG 시그니처 일부만
        assertThat(SeatClaimImageStore.detectContentType(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'))).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType(bytes(0x89, 'P', 'N', 'G'))).isEmpty();
        assertThat(SeatClaimImageStore.detectContentType(bytes(0xFF, 0xD8))).isEmpty();
    }

    @Test
    void storesUnderGeneratedUuidNameAndCreatesDirectory(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("a/b/claims");
        SeatClaimImageStore store = new SeatClaimImageStore(dir.toString());

        String key = store.store(bytes(0xFF, 0xD8, 0xFF, 1, 2, 3));

        assertThat(SeatClaimImageStore.isValidKey(key)).isTrue();
        assertThat(key).isEqualTo(key.toLowerCase());
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(key);
        }
        assertThat(store.read(key)).hasValueSatisfying(b -> assertThat(b).containsExactly(0xFF - 256, 0xD8 - 256, 0xFF - 256, 1, 2, 3));
        assertThat(store.delete(key)).isTrue();
        assertThat(store.read(key)).isEmpty();
        assertThat(store.delete(key)).as("이미 없어도 성공").isTrue();
    }

    @Test
    void keysThatCouldEscapeTheDirectoryAreRefused(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("claims");
        Files.createDirectories(dir);
        Files.write(tmp.resolve("secret.txt"), new byte[]{1});
        SeatClaimImageStore store = new SeatClaimImageStore(dir.toString());

        for (String evil : List.of("../secret.txt", "..\\secret.txt", "/etc/passwd", "C:\\Windows\\win.ini",
                "00000000-0000-0000-0000-000000000000/../../secret.txt", "../claims/00000000-0000-0000-0000-000000000000",
                "00000000-0000-0000-0000-00000000000A", "", " ", "null")) {
            assertThat(SeatClaimImageStore.isValidKey(evil)).as(evil).isFalse();
            assertThatThrownBy(() -> store.read(evil)).as(evil).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.delete(evil)).as(evil).isFalse();
        }
        assertThat(SeatClaimImageStore.isValidKey(null)).isFalse();
        assertThat(Files.exists(tmp.resolve("secret.txt"))).isTrue();
    }
}
