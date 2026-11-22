package com.matjari.service;

import com.matjari.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채팅 사진 보관 정리 단위 테스트(저장소 mock, 파일은 임시 폴더). '닫힌 뒤 30일'·'처리 전 신고 제외'를 고르는 SQL 자체는
 * ChatImageSchemaMysqlTest(실제 MySQL)가 확인하고, 여기서는 기준 시각(cutoff) 계산과 파일·기록 처리 순서를 본다.
 */
class ChatImageCleanupServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");

    @TempDir
    Path tmp;

    private ChatMessageRepository repo;
    private ChatImageStore store;
    private ChatImageCleanupService service;

    @BeforeEach
    void setUp() {
        repo = mock(ChatMessageRepository.class);
        store = new ChatImageStore(tmp.toString());
        Clock clock = Clock.fixed(NOW, ZONE);
        service = new ChatImageCleanupService(repo, store, clock, 30, 60, 200);
    }

    private static ChatMessageRepository.ExpiredImage target(long id, String key) {
        return new ChatMessageRepository.ExpiredImage() {
            public Long getId() {
                return id;
            }

            public String getImageKey() {
                return key;
            }
        };
    }

    private String put(String content) {
        return store.store(content.getBytes());
    }

    private void age(String key, long minutes) throws IOException {
        Files.setLastModifiedTime(tmp.resolve("chat").resolve(key), FileTime.from(NOW.minusSeconds(minutes * 60)));
    }

    @Test
    void 기준_시각은_지금에서_보관_일수를_뺀_값이고_파일을_지운_뒤_지운_시각을_기록한다() {
        String key = put("photo");
        when(repo.findImagesPastRetention(any(), anyInt())).thenReturn(List.of(target(7L, key)));

        ChatImageCleanupService.Result r = service.cleanup();

        assertThat(r.expiredDeleted()).isEqualTo(1);
        assertThat(store.read(key)).isEmpty();
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        verify(repo).findImagesPastRetention(eq(now.minusDays(30)), eq(200));
        verify(repo).markImageDeleted(7L, now);
    }

    @Test
    void 파일이_이미_없어도_기록은_남기고_대상이_없으면_아무것도_하지_않는다() {
        when(repo.findImagesPastRetention(any(), anyInt())).thenReturn(List.of(target(8L, "00000000-0000-4000-8000-000000000008")));
        assertThat(service.cleanup().expiredDeleted()).isEqualTo(1);
        verify(repo).markImageDeleted(eq(8L), any());

        ChatMessageRepository none = mock(ChatMessageRepository.class);
        when(none.findImagesPastRetention(any(), anyInt())).thenReturn(List.of());
        assertThat(new ChatImageCleanupService(none, store, Clock.fixed(NOW, ZONE), 30, 60, 200).cleanup().expiredDeleted()).isZero();
        verify(none, never()).markImageDeleted(any(), any());
    }

    @Test
    void 키가_UUID_형식이_아니면_파일에_손대지_않고_기록도_하지_않는다() {
        when(repo.findImagesPastRetention(any(), anyInt())).thenReturn(List.of(target(9L, "../../etc/passwd")));

        assertThat(service.cleanup().expiredDeleted()).isZero();
        verify(repo, never()).markImageDeleted(any(), any());
    }

    @Test
    void 고아_파일은_유예_시간이_지난_것만_지우고_살아_있는_행이_가리키는_파일은_남긴다() throws IOException {
        String orphan = put("orphan");
        String live = put("live");
        String fresh = put("fresh");
        age(orphan, 120);
        age(live, 120);
        age(fresh, 5);   // 60분 유예 안: 저장 직후 INSERT 가 커밋되기 전일 수 있다
        Files.writeString(tmp.resolve("chat").resolve("not-a-uuid.txt"), "x");
        when(repo.findImagesPastRetention(any(), anyInt())).thenReturn(List.of());
        when(repo.findLiveImageKeys(any())).thenAnswer(inv -> {
            java.util.Collection<String> keys = inv.getArgument(0);
            return keys.contains(live) ? List.of(live) : List.of();
        });

        ChatImageCleanupService.Result r = service.cleanup();

        assertThat(r.orphanDeleted()).isEqualTo(1);
        assertThat(store.read(orphan)).isEmpty();
        assertThat(store.read(live)).isPresent();
        assertThat(store.read(fresh)).isPresent();
        assertThat(Files.exists(tmp.resolve("chat").resolve("not-a-uuid.txt"))).isTrue();
    }

    @Test
    void 살아_있는_파일이_batch보다_많아도_그_뒤의_고아를_찾고_batch는_지운_개수로_센다() throws IOException {
        ChatImageCleanupService small = new ChatImageCleanupService(repo, store, Clock.fixed(NOW, ZONE), 30, 60, 3);
        java.util.Set<String> live = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {   // batch(3)보다 많은 살아 있는 파일
            String k = put("live" + i);
            age(k, 120);
            live.add(k);
        }
        java.util.List<String> orphans = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String k = put("orphan" + i);
            age(k, 120);
            orphans.add(k);
        }
        when(repo.findImagesPastRetention(any(), anyInt())).thenReturn(List.of());
        when(repo.findLiveImageKeys(any())).thenAnswer(inv -> {
            java.util.Collection<String> keys = inv.getArgument(0);
            return keys.stream().filter(live::contains).toList();
        });

        assertThat(small.cleanup().orphanDeleted()).as("이번 주기 최대 batch 개").isEqualTo(3);
        assertThat(small.cleanup().orphanDeleted()).as("다음 주기에 나머지").isEqualTo(2);
        for (String k : live) {
            assertThat(store.read(k)).isPresent();
        }
        for (String k : orphans) {
            assertThat(store.read(k)).isEmpty();
        }
    }

    @Test
    void 한_단계가_실패해도_다른_단계는_계속한다() throws IOException {
        String orphan = put("orphan");
        age(orphan, 120);
        when(repo.findImagesPastRetention(any(), anyInt())).thenThrow(new IllegalStateException("db"));
        when(repo.findLiveImageKeys(any())).thenReturn(List.of());

        ChatImageCleanupService.Result r = service.cleanup();

        assertThat(r.expiredDeleted()).isZero();
        assertThat(r.orphanDeleted()).isEqualTo(1);
    }
}
