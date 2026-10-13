package com.seatswap.service;

import com.seatswap.domain.SeatClaim;
import com.seatswap.repository.SeatClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 좌석 인증 사진 정리 작업: 처리 뒤 삭제가 실패해 남은 사진과 요청 행이 없는 고아 파일을 지우고, 처리 중(PENDING) 요청의 사진은 지우지 않는다. */
class SeatClaimImageCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");

    @TempDir
    Path dir;

    private SeatClaimRepository repository;
    private SeatClaimImageStore store;
    private SeatClaimImageCleanupService service;

    @BeforeEach
    void setUp() {
        repository = mock(SeatClaimRepository.class);
        store = new SeatClaimImageStore(dir.toString());
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        service = new SeatClaimImageCleanupService(repository, store, clock, mock(PlatformTransactionManager.class), 60, 200);
        when(repository.findProcessedIdsWithImage(any(), any(Pageable.class))).thenReturn(List.of());
        when(repository.findExistingImageKeys(anyCollection())).thenReturn(List.of());
    }

    /** 키 이름의 파일을 만들고 마지막 수정 시각을 NOW 기준 ageMinutes 분 전으로 맞춘다. */
    private String file(long ageMinutes) throws IOException {
        String key = UUID.randomUUID().toString();
        Path path = dir.resolve(key);
        Files.write(path, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(path, FileTime.from(NOW.minusSeconds(ageMinutes * 60)));
        return key;
    }

    @Test
    void 요청_행이_없는_오래된_파일만_고아로_지운다() throws IOException {
        String orphan = file(120);
        String young = file(5);          // 저장 직후 INSERT 가 커밋되기 전일 수 있어 건드리지 않는다
        String referenced = file(120);   // 처리 중 요청이 가리키는 사진
        Path foreign = dir.resolve("notes.txt");
        Files.writeString(foreign, "x");
        Files.setLastModifiedTime(foreign, FileTime.from(NOW.minusSeconds(7200)));
        when(repository.findExistingImageKeys(anyCollection())).thenReturn(List.of(referenced));

        SeatClaimImageCleanupService.Result result = service.cleanup();

        assertThat(result.orphanFilesDeleted()).isEqualTo(1);
        assertThat(dir.resolve(orphan)).doesNotExist();
        assertThat(dir.resolve(young)).exists();
        assertThat(dir.resolve(referenced)).exists();
        assertThat(foreign).as("UUID 이름이 아닌 파일은 건드리지 않는다").exists();
    }

    @Test
    void 처리됐는데_사진이_남은_요청은_파일을_지우고_삭제를_기록한다() throws IOException {
        String key = file(300);
        SeatClaim claim = mock(SeatClaim.class);
        when(claim.getImageKey()).thenReturn(key);
        when(claim.isPending()).thenReturn(false);
        when(claim.isImageDeleted()).thenReturn(false);
        when(repository.findProcessedIdsWithImage(any(), any(Pageable.class))).thenReturn(List.of(7L));
        when(repository.findById(7L)).thenReturn(Optional.of(claim));
        when(repository.findExistingImageKeys(anyCollection())).thenReturn(List.of(key));

        SeatClaimImageCleanupService.Result result = service.cleanup();

        assertThat(result.processedFilesDeleted()).isEqualTo(1);
        assertThat(dir.resolve(key)).doesNotExist();
        verify(claim).markImageDeleted(any());
        verify(repository).save(claim);
    }

    @Test
    void 파일이_이미_없어도_처리된_요청의_삭제를_기록한다() {
        SeatClaim claim = mock(SeatClaim.class);
        when(claim.getImageKey()).thenReturn(UUID.randomUUID().toString());
        when(repository.findProcessedIdsWithImage(any(), any(Pageable.class))).thenReturn(List.of(8L));
        when(repository.findById(8L)).thenReturn(Optional.of(claim));

        assertThat(service.cleanup().processedFilesDeleted()).isEqualTo(1);
        verify(claim).markImageDeleted(any());
    }

    @Test
    void 처리_중이거나_이미_기록된_요청의_사진은_건드리지_않는다() throws IOException {
        String pendingKey = file(300);
        SeatClaim pending = mock(SeatClaim.class);
        when(pending.getImageKey()).thenReturn(pendingKey);
        when(pending.isPending()).thenReturn(true);   // 조회 뒤 상태가 바뀐 경우까지 방어한다
        when(repository.findProcessedIdsWithImage(any(), any(Pageable.class))).thenReturn(List.of(9L));
        when(repository.findById(9L)).thenReturn(Optional.of(pending));
        when(repository.findExistingImageKeys(anyCollection())).thenReturn(List.of(pendingKey));

        SeatClaimImageCleanupService.Result result = service.cleanup();

        assertThat(result.processedFilesDeleted()).isZero();
        assertThat(dir.resolve(pendingKey)).exists();
        verify(pending, never()).markImageDeleted(any());
    }

    @Test
    void 한_단계가_실패해도_다른_단계는_계속한다() throws IOException {
        String orphan = file(120);
        when(repository.findProcessedIdsWithImage(any(), any(Pageable.class))).thenThrow(new IllegalStateException("db down"));

        SeatClaimImageCleanupService.Result result = service.cleanup();

        assertThat(result.processedFilesDeleted()).isZero();
        assertThat(result.orphanFilesDeleted()).isEqualTo(1);
        assertThat(dir.resolve(orphan)).doesNotExist();
    }

    @Test
    void 저장_디렉터리가_없으면_아무것도_하지_않는다() {
        SeatClaimImageStore missing = new SeatClaimImageStore(dir.resolve("none").toString());
        assertThat(missing.listKeysOlderThan(NOW, 10)).isEmpty();
        assertThat(Set.copyOf(store.listKeysOlderThan(NOW, 10))).isEmpty();
    }
}
