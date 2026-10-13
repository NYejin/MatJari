package com.seatswap.service;

import com.seatswap.domain.SeatClaim;
import com.seatswap.repository.SeatClaimRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 좌석 인증 사진 정리 작업. 처리(승인·거절)가 끝나면 커밋 뒤 한 번만 파일을 지우므로(SeatClaimService), 그 삭제가 실패하거나 서버가 그 사이 멈추면
 * 파일이 남는다. 이 작업이 주기적으로 두 가지를 정리해 방침의 "처리가 끝나면 삭제"를 지킨다.
 * <ol>
 *   <li>처리된 요청인데 사진 삭제가 기록되지 않은 것: 파일을 지우고 {@code image_deleted_at} 을 기록한다(이미 없어도 성공으로 본다).</li>
 *   <li>요청 행이 없는 고아 파일(저장 직후 서버가 멈춰 INSERT 가 안 된 경우 등): 디스크에서 찾아 지운다. 저장 직후 INSERT 가 커밋되기까지의
 *       짧은 틈을 지키려고 {@code grace-minutes}(기본 60분)보다 오래된 파일만 본다.</li>
 * </ol>
 * 처리 중(PENDING) 요청의 사진은 행이 있어 어떤 경우에도 지우지 않는다. 한 번에 {@code batch}(기본 200)건까지만 처리하고 나머지는 다음 주기에 한다.
 * 파일 이름은 UUID 형식만 다루며(SeatClaimImageStore) 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class SeatClaimImageCleanupService {

    /** 한 번 실행의 결과(테스트·로그용). */
    public record Result(int processedFilesDeleted, int orphanFilesDeleted) {
    }

    private static final int IN_CHUNK = 100;

    private final SeatClaimRepository claimRepository;
    private final SeatClaimImageStore imageStore;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Duration grace;
    private final int batch;

    public SeatClaimImageCleanupService(SeatClaimRepository claimRepository, SeatClaimImageStore imageStore, Clock clock,
                                        PlatformTransactionManager transactionManager,
                                        @Value("${seatswap.upload.cleanup.grace-minutes:60}") long graceMinutes,
                                        @Value("${seatswap.upload.cleanup.batch:200}") int batch) {
        this.claimRepository = claimRepository;
        this.imageStore = imageStore;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.grace = Duration.ofMinutes(Math.max(1, graceMinutes));
        this.batch = Math.max(1, batch);
    }

    /** 두 정리를 차례로 실행한다. 어느 단계가 실패해도 다른 단계는 계속하고, 실패는 로그(종류만)로 남긴다. */
    public Result cleanup() {
        int processed = 0;
        int orphans = 0;
        try {
            processed = deleteProcessedImages();
        } catch (RuntimeException e) {
            log.warn("Seat claim image cleanup (processed) failed: {}", e.getClass().getSimpleName());
        }
        try {
            orphans = deleteOrphanFiles();
        } catch (RuntimeException e) {
            log.warn("Seat claim image cleanup (orphans) failed: {}", e.getClass().getSimpleName());
        }
        if (processed > 0 || orphans > 0) {
            log.info("Seat claim image cleanup: processedFilesDeleted={}, orphanFilesDeleted={}", processed, orphans);
        }
        return new Result(processed, orphans);
    }

    private int deleteProcessedImages() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(grace);
        List<Long> ids = claimRepository.findProcessedIdsWithImage(cutoff, PageRequest.of(0, batch));
        int deleted = 0;
        for (Long id : ids) {
            SeatClaim claim = claimRepository.findById(id).orElse(null);
            if (claim == null || claim.isPending() || claim.isImageDeleted()) {
                continue;
            }
            if (!imageStore.delete(claim.getImageKey())) {
                continue;   // 이번엔 못 지웠다 -> 다음 주기에 다시 시도
            }
            transactionTemplate.executeWithoutResult(s -> claimRepository.findById(id).ifPresent(c -> {
                c.markImageDeleted(LocalDateTime.now(clock));
                claimRepository.save(c);
            }));
            deleted++;
        }
        return deleted;
    }

    private int deleteOrphanFiles() {
        List<String> keys = imageStore.listKeysOlderThan(Instant.now(clock).minus(grace), batch);
        int deleted = 0;
        for (int from = 0; from < keys.size(); from += IN_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IN_CHUNK));
            Set<String> referenced = new HashSet<>(claimRepository.findExistingImageKeys(chunk));
            for (String key : chunk) {
                if (!referenced.contains(key) && imageStore.delete(key)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
