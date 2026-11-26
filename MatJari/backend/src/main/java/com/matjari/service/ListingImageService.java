package com.matjari.service;

import com.matjari.domain.ExchangeRequest;
import com.matjari.domain.ListingImage;
import com.matjari.domain.Ticket;
import com.matjari.dto.response.ListingImageRef;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ListingImageIndex;
import com.matjari.repository.ListingImageRepository;
import com.matjari.repository.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 티켓('내 자리')·교환 희망 조건('원하는 자리')에 사진 붙이기 (V33). 각각 최대 3장. 열람 권한 판정은 {@link ListingImageAccessService}.
 *
 * <ul>
 *   <li><b>올리기</b>: 본인 것만(아니면 404), 티켓이 ACTIVE(요청은 OPEN)일 때만(아니면 409 TICKET_NOT_ACTIVE, 삭제된 요청은 409 REQUEST_DELETED),
 *       3장 상한(422 IMAGE_LIMIT_REACHED). 잠금 없는 사전 확인으로 남의 것·닫힌 것에는 이미지 처리(CPU)를 시키지 않고, 처리 시도는 사용자당 분당·전체 동시 수로 막는다
 *       ({@link ListingImageRateLimiter}, 동시 수는 채팅 사진과 같은 세마포어). 처리(디코드·재인코딩으로 EXIF·GPS 제거)는 <b>트랜잭션 밖</b>에서 하고,
 *       트랜잭션의 첫 쿼리가 대상 행 FOR UPDATE 다: 티켓 사진은 티켓 행, 요청 사진은 요청 행만 잠근다(잠금 순서 티켓 → 요청 → 매칭, 요청만 다루는 경로는 티켓을 잠그지 않는다).
 *       users 를 먼저 잠그지 않는다: 탈퇴가 티켓·요청 잠금 뒤에 users 를 잡으므로 users 를 먼저 잡으면 교착한다(개발 규칙). 한 대상의 3장 상한은 대상 행 잠금으로 직렬화된다.
 *       파일은 INSERT 직전에 쓰고 실패·롤백이면 지운다.</li>
 *   <li><b>지우기</b>: 행을 지우고 커밋 뒤 파일을 지운다(멱등 204, 남의 것은 404).</li>
 *   <li><b>요청 삭제</b>({@link #purgeForRequest}, 호출자의 트랜잭션 안)와 <b>회원 탈퇴</b>({@link #purgeForUser})는 커밋 뒤 파일을 즉시 지운다.
 *       요청 삭제는 행을 지우고, 탈퇴는 행에 deleted_at 을 남긴다(탈퇴 사용자의 기록 보존).</li>
 *   <li><b>조회</b>: 후보·매칭·내 목록 응답용 사진 참조를 id 묶음으로 한 번에 읽는다({@link #index}).</li>
 * </ul>
 * 로그에는 사용자 id·사진 id 만 남긴다.
 */
@Slf4j
@Service
public class ListingImageService {

    static final String TICKET_NOT_FOUND_MESSAGE = "티켓을 찾을 수 없습니다.";
    static final String REQUEST_NOT_FOUND_MESSAGE = "교환 희망 조건을 찾을 수 없습니다.";
    static final String LIMIT_CODE = "IMAGE_LIMIT_REACHED";
    static final String NOT_ACTIVE_CODE = "TICKET_NOT_ACTIVE";
    static final String REQUEST_DELETED_CODE = "REQUEST_DELETED";

    private final ListingImageRepository imageRepository;
    private final ListingImageStore store;
    private final ListingImageRateLimiter limiter;
    private final ChatImageProcessingGate gate;
    private final TicketRepository ticketRepository;
    private final ExchangeRequestRepository requestRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ListingImageService(ListingImageRepository imageRepository, ListingImageStore store,
                               ListingImageRateLimiter limiter, ChatImageProcessingGate gate,
                               TicketRepository ticketRepository, ExchangeRequestRepository requestRepository,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.imageRepository = imageRepository;
        this.store = store;
        this.limiter = limiter;
        this.gate = gate;
        this.ticketRepository = ticketRepository;
        this.requestRepository = requestRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 올리기

    /** 티켓 사진 올리기. 201 용 참조를 돌려준다. */
    public ListingImageRef uploadForTicket(Long userId, Long ticketId, MultipartFile image) {
        // 잠금 없는 사전 확인: 남의 티켓은 존재를 드러내지 않고 404, 닫힌 티켓·상한은 이미지 처리 전에 거른다
        Ticket seen = ticketRepository.findById(ticketId)
                .filter(t -> t.isOwnedBy(userId)).orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
        ensureTicketActive(seen);
        if (imageRepository.countByTicketIdAndDeletedAtIsNull(ticketId) >= ListingImage.MAX_PER_TARGET) {
            throw limitReached();
        }
        ChatImageProcessor.Processed processed = process(userId, image);
        String[] stored = {null};
        try {
            return tx.execute(status -> {
                Ticket locked = ticketRepository.findByIdForUpdate(ticketId)   // 첫 쿼리
                        .filter(t -> t.isOwnedBy(userId)).orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
                ensureTicketActive(locked);
                List<ListingImage> live = imageRepository.findByTicketIdAndDeletedAtIsNull(ticketId);
                if (live.size() >= ListingImage.MAX_PER_TARGET) {
                    throw limitReached();
                }
                limiter.acquireDaily(userId);
                stored[0] = store.store(processed.bytes());
                ListingImage saved = imageRepository.saveAndFlush(
                        ListingImage.ofTicket(userId, ticketId, stored[0], freeSlot(live)));
                log.info("Listing image added: userId={}, imageId={}, target=ticket", userId, saved.getId());
                return new ListingImageRef(saved.getId());
            });
        } catch (RuntimeException e) {
            discardStored(stored[0]);   // 롤백·INSERT 실패면 방금 쓴 파일을 지운다(못 지워도 정리 작업이 고아로 지운다)
            throw e;
        }
    }

    /** 교환 희망 조건 사진 올리기. 요청 행만 잠근다(티켓은 잠그지 않는다). */
    public ListingImageRef uploadForRequest(Long userId, Long requestId, MultipartFile image) {
        ExchangeRequest seen = requestRepository.findById(requestId)
                .filter(r -> userId.equals(ticketRepository.findOwnerIdById(r.getTicket().getId()).orElse(null)))
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
        ensureRequestOpen(seen);
        if (imageRepository.countByRequestIdAndDeletedAtIsNull(requestId) >= ListingImage.MAX_PER_TARGET) {
            throw limitReached();
        }
        ChatImageProcessor.Processed processed = process(userId, image);
        String[] stored = {null};
        try {
            return tx.execute(status -> {
                ExchangeRequest locked = requestRepository.findByIdForUpdate(requestId)   // 첫 쿼리
                        .filter(r -> r.getTicket().isOwnedBy(userId)).orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
                ensureRequestOpen(locked);
                List<ListingImage> live = imageRepository.findByRequestIdAndDeletedAtIsNull(requestId);
                if (live.size() >= ListingImage.MAX_PER_TARGET) {
                    throw limitReached();
                }
                limiter.acquireDaily(userId);
                stored[0] = store.store(processed.bytes());
                ListingImage saved = imageRepository.saveAndFlush(
                        ListingImage.ofRequest(userId, requestId, stored[0], freeSlot(live)));
                log.info("Listing image added: userId={}, imageId={}, target=request", userId, saved.getId());
                return new ListingImageRef(saved.getId());
            });
        } catch (RuntimeException e) {
            discardStored(stored[0]);
            throw e;
        }
    }

    private ChatImageProcessor.Processed process(Long userId, MultipartFile image) {
        byte[] data = readUpload(image);
        limiter.countAttempt(userId);   // 검증에 실패한 시도도 센다
        return gate.runWithPermit(() -> ChatImageProcessor.process(data));
    }

    private static byte[] readUpload(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new FieldValidationException("image", "올릴 이미지를 선택해 주세요.");
        }
        if (image.getSize() > ChatImageProcessor.MAX_BYTES) {
            throw new MaxUploadSizeExceededException(ChatImageProcessor.MAX_BYTES);
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다.", e);
        }
    }

    private void discardStored(String key) {
        if (key != null) {
            store.delete(key);
        }
    }

    private static int freeSlot(List<ListingImage> live) {
        Set<Integer> used = new HashSet<>();
        live.forEach(i -> used.add((int) i.getSortOrder()));
        int slot = 0;
        while (used.contains(slot)) {
            slot++;
        }
        return slot;
    }

    private static void ensureTicketActive(Ticket ticket) {
        if (!ticket.isActive()) {
            throw new ConflictException("교환 중이 아닌 티켓에는 사진을 붙일 수 없어요.", Map.of("code", NOT_ACTIVE_CODE));
        }
    }

    private static void ensureRequestOpen(ExchangeRequest request) {
        if (request.isDeleted()) {
            throw new ConflictException(ExchangeRequestService.REQUEST_DELETED_MESSAGE,
                    Map.of("code", REQUEST_DELETED_CODE));
        }
        if (!request.isOpen()) {
            throw new ConflictException("닫힌 교환 희망 조건에는 사진을 붙일 수 없어요.", Map.of("code", NOT_ACTIVE_CODE));
        }
    }

    private static BusinessRuleException limitReached() {
        return new BusinessRuleException(LIMIT_CODE, "사진은 최대 " + ListingImage.MAX_PER_TARGET + "장까지 붙일 수 있어요.");
    }

    // ---------------------------------------------------------------- 지우기

    /** 티켓 사진 지우기(멱등). 남의 티켓은 404. 행 삭제 뒤 커밋 뒤 파일 삭제. */
    public void deleteForTicket(Long userId, Long ticketId, Long imageId) {
        tx.executeWithoutResult(status -> {
            Ticket ticket = ticketRepository.findById(ticketId)
                    .filter(t -> t.isOwnedBy(userId)).orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
            imageRepository.findById(imageId)
                    .filter(i -> ticket.getId().equals(i.getTicketId()))
                    .ifPresent(this::removeRow);
        });
    }

    /** 희망 조건 사진 지우기(멱등). 남의 요청은 404. */
    public void deleteForRequest(Long userId, Long requestId, Long imageId) {
        tx.executeWithoutResult(status -> {
            ExchangeRequest request = requestRepository.findById(requestId)
                    .filter(r -> r.getTicket().isOwnedBy(userId)).orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
            imageRepository.findById(imageId)
                    .filter(i -> request.getId().equals(i.getRequestId()))
                    .ifPresent(this::removeRow);
        });
    }

    private void removeRow(ListingImage image) {
        String key = image.getImageKey();
        Long id = image.getId();
        imageRepository.delete(image);
        imageRepository.flush();
        afterCommit(() -> store.delete(key));
        log.info("Listing image removed: imageId={}", id);
    }

    /**
     * 교환 희망 조건 삭제 때(호출자의 트랜잭션 안): 그 요청의 사진 행을 지우고 커밋 뒤 파일을 지운다. 희망 조건이 사라지므로 사진도 곧바로 없앤다.
     * 호출자가 이미 요청 행을 잠갔고, 이 메서드는 listing_image 행만 만진다(알림 등 다른 잠금 없음).
     */
    public void purgeForRequest(Long requestId) {
        List<ListingImage> rows = imageRepository.findByRequestIdAndDeletedAtIsNull(requestId);
        if (rows.isEmpty()) {
            return;
        }
        List<String> keys = rows.stream().map(ListingImage::getImageKey).toList();
        imageRepository.deleteAll(rows);
        imageRepository.flush();
        afterCommit(() -> keys.forEach(store::delete));
        log.info("Listing images purged for deleted request: requestId={}, count={}", requestId, rows.size());
    }

    /**
     * 회원 탈퇴 때(호출자의 트랜잭션 안, 티켓·매칭·요청·users 잠금 뒤): 그 사용자가 올린 사진 파일을 커밋 뒤 즉시 지우고 행에 deleted_at 을 기록한다.
     * 이후 열람은 소유자·후보·매칭 관계와 무관하게 410(권한이 있는 경우)이다.
     */
    public int purgeForUser(Long userId) {
        List<ListingImage> rows = imageRepository.findByOwnerIdAndDeletedAtIsNull(userId);
        if (rows.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> keys = new ArrayList<>(rows.size());
        for (ListingImage row : rows) {
            keys.add(row.getImageKey());
            row.markFileDeleted(now);
        }
        imageRepository.saveAll(rows);
        imageRepository.flush();
        afterCommit(() -> keys.forEach(store::delete));
        log.info("Listing images purged for withdrawn user: userId={}, count={}", userId, rows.size());
        return rows.size();
    }

    /** 활성 트랜잭션이 있으면 커밋 뒤에, 없으면(테스트·트랜잭션 밖 호출) 곧바로 실행한다. 실패는 삼킨다(정리 작업이 다시 지운다). */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run(action);
                }
            });
        } else {
            run(action);
        }
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Listing image file cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 조회

    /**
     * 후보·매칭·내 목록 응답에 넣을 사진 참조를 한 번에 읽는다(id 묶음 IN 조회 2번, 파일이 지워지지 않은 것만, 순서대로). 빈 입력은 쿼리를 하지 않는다.
     */
    public ListingImageIndex index(Collection<Long> ticketIds, Collection<Long> requestIds) {
        Map<Long, List<ListingImageRef>> byTicket = new LinkedHashMap<>();
        if (ticketIds != null && !ticketIds.isEmpty()) {
            for (ListingImage i : imageRepository
                    .findByTicketIdInAndDeletedAtIsNullOrderByTicketIdAscSortOrderAscIdAsc(new HashSet<>(ticketIds))) {
                byTicket.computeIfAbsent(i.getTicketId(), k -> new ArrayList<>()).add(new ListingImageRef(i.getId()));
            }
        }
        Map<Long, List<ListingImageRef>> byRequest = new LinkedHashMap<>();
        if (requestIds != null && !requestIds.isEmpty()) {
            for (ListingImage i : imageRepository
                    .findByRequestIdInAndDeletedAtIsNullOrderByRequestIdAscSortOrderAscIdAsc(new HashSet<>(requestIds))) {
                byRequest.computeIfAbsent(i.getRequestId(), k -> new ArrayList<>()).add(new ListingImageRef(i.getId()));
            }
        }
        return new ListingImageIndex(byTicket, byRequest);
    }
}
