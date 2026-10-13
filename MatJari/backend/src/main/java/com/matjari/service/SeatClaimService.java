package com.matjari.service;

import com.matjari.domain.AdminReviewStatus;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.SeatClaim;
import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.SeatClaimDtos.AdminSeatClaimResponse;
import com.matjari.dto.response.SeatClaimDtos.Holder;
import com.matjari.dto.response.SeatClaimDtos.SeatClaimResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.exception.MatJariException;
import com.matjari.repository.ExchangeTicketLockRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.SeatClaimRepository;
import com.matjari.repository.TicketRepository;
import com.matjari.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 좌석 소유 인증 요청 '내 티켓 인증' (admin-spec B). 이미 다른 사람이 활성 티켓으로 등록한 좌석의 실제 소유자가
 * 예매내역 사진과 함께 요청하면 관리자가 승인(현재 그 좌석의 활성 티켓을 시스템이 내림)하거나 거절한다.
 *
 * 생성 검증 순서: 회차 존재(400 sessionId) -> 마감(422 SESSION_CLOSED) -> 좌석·메모·이미지 형식(400, 모아서) ->
 * 내 활성 티켓 중복(409 MY_TICKET_ALREADY_REGISTERED) -> 그 좌석에 활성 티켓 없음(422 SEAT_NOT_REGISTERED) ->
 * PENDING 중복(409 SEAT_CLAIM_ALREADY_PENDING) / 사용자 상한(422 SEAT_CLAIM_LIMIT_REACHED) -> 파일 저장 -> INSERT.
 * 사용자 행 FOR UPDATE 가 첫 쿼리라 같은 사용자의 동시 요청이 직렬화된다. 실패·롤백 시 저장한 파일은 지운다.
 *
 * 처리(승인·거절) 잠금 순서: 인증 요청 행 -> 티켓 id↑ -> 교환 요청 id↑ -> 매칭 (claim 을 나중에 잠그는 경로는 없다).
 * 처리가 커밋된 뒤에 사진 파일을 지우고(afterCommit, 실패해도 처리 결과는 유지, 로그만) 성공하면 별도 짧은 트랜잭션에서
 * image_deleted_at 을 기록한다. 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class SeatClaimService {

    static final String SESSION_NOT_FOUND_MESSAGE = "존재하지 않는 회차입니다.";
    static final String SESSION_CLOSED_MESSAGE = "회차 당일이 지나 인증 요청을 할 수 없습니다.";
    static final String CLAIM_NOT_FOUND_MESSAGE = "인증 요청을 찾을 수 없습니다.";
    static final int MAX_PAGE_SIZE = 100;

    private final SeatClaimRepository claimRepository;
    private final TicketRepository ticketRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ExchangeTicketLockRepository lockRepository;
    private final TicketService ticketService;
    private final SeatClaimImageStore imageStore;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate newTransactionTemplate;
    /** 처리(승인·거절) 전용: READ COMMITTED. REPEATABLE READ 스냅샷이 잠금 읽기 전에 고정되면 예약 잠금 검사가 낡은 값을 읽는다. */
    private final TransactionTemplate reviewTransactionTemplate;
    private final int maxPendingPerUser;
    private final int maxRowNumber;
    private final int maxColNumber;

    public SeatClaimService(SeatClaimRepository claimRepository,
                            TicketRepository ticketRepository,
                            PerformanceSessionRepository sessionRepository,
                            UserRepository userRepository,
                            ExchangeTicketLockRepository lockRepository,
                            TicketService ticketService,
                            SeatClaimImageStore imageStore,
                            SessionTimePolicy sessionTimePolicy,
                            PlatformTransactionManager transactionManager,
                            @Value("${matjari.seat-claim.max-pending-per-user:5}") int maxPendingPerUser,
                            @Value("${ticket.max-row-number:999}") int maxRowNumber,
                            @Value("${ticket.max-col-number:999}") int maxColNumber) {
        this.claimRepository = claimRepository;
        this.ticketRepository = ticketRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.lockRepository = lockRepository;
        this.ticketService = ticketService;
        this.imageStore = imageStore;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.reviewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.reviewTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.newTransactionTemplate = new TransactionTemplate(transactionManager);
        this.newTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxPendingPerUser = maxPendingPerUser;
        this.maxRowNumber = maxRowNumber;
        this.maxColNumber = maxColNumber;
    }

    /** 사진이 삭제된 요청의 사진 조회 -> 410 IMAGE_DELETED. */
    public static class ImageDeletedException extends MatJariException {
        public ImageDeletedException() {
            super("처리가 끝나 사진이 삭제되었습니다.");
        }

        public String getCode() {
            return "IMAGE_DELETED";
        }
    }

    public record ImageData(String contentType, byte[] bytes) {}

    // ------------------------------------------------------------------ 생성·조회 (사용자)

    public SeatClaimResponse create(Long userId, Long sessionId, String zone, String row, String col,
                                    String memo, MultipartFile image) {
        byte[] data = readImage(image);   // 없으면 null (아래에서 400 image 로 모은다)
        try {
            return createWithImage(userId, sessionId, zone, row, col, memo, data);
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, SeatClaim.UNIQUE_PENDING_SEAT)) {
                throw e;
            }
            throw pendingExists();
        }
    }

    SeatClaimResponse createWithImage(Long userId, Long sessionId, String zone, String row, String col,
                                      String memo, byte[] data) {
        if (data != null && data.length > SeatClaim.IMAGE_MAX_BYTES) {
            throw new MaxUploadSizeExceededException(SeatClaim.IMAGE_MAX_BYTES);
        }
        return transactionTemplate.execute(status -> {
            // 사용자 행 잠금이 첫 쿼리여야 한다(TicketService.create 와 같은 이유: RR 스냅샷 고정 전에 직렬화).
            userRepository.findByIdForUpdate(userId)
                    .filter(u -> !u.isWithdrawn())
                    .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
            if (sessionId == null) {
                throw new FieldValidationException("sessionId", "회차를 선택해주세요.");
            }
            PerformanceSession session = sessionRepository.findWithPerformanceById(sessionId)
                    .orElseThrow(() -> new FieldValidationException("sessionId", SESSION_NOT_FOUND_MESSAGE));
            if (!sessionTimePolicy.now().isBefore(session.registrationDeadline())) {
                throw new BusinessRuleException("SESSION_CLOSED", SESSION_CLOSED_MESSAGE);
            }

            Map<String, String> errors = new LinkedHashMap<>();
            String[] z = normalize(errors, SeatKeyNormalizer.ZONE,
                    () -> new String[]{SeatKeyNormalizer.zoneLabel(zone), SeatKeyNormalizer.zoneKey(zone)});
            String[] r = normalize(errors, SeatKeyNormalizer.ROW,
                    () -> new String[]{SeatKeyNormalizer.rowLabel(row), SeatKeyNormalizer.rowKey(row, maxRowNumber)});
            String[] c = normalize(errors, SeatKeyNormalizer.COL,
                    () -> new String[]{SeatKeyNormalizer.colLabel(col), SeatKeyNormalizer.colKey(col, maxColNumber)});
            String cleanMemo = Performance.cleanDisplayText(memo);
            if (cleanMemo != null && cleanMemo.length() > SeatClaim.MEMO_MAX_LENGTH) {
                errors.put("memo", "메모는 " + SeatClaim.MEMO_MAX_LENGTH + "자 이하로 입력해주세요.");
            }
            String contentType = null;
            if (data == null || data.length == 0) {
                errors.put("image", "예매내역 사진을 첨부해주세요.");
            } else {
                contentType = SeatClaimImageStore.detectContentType(data).orElse(null);
                if (contentType == null) {
                    errors.put("image", "JPEG, PNG, WebP 형식의 사진만 올릴 수 있습니다.");
                }
            }
            if (!errors.isEmpty()) {
                throw FieldValidationException.ofAll(errors);
            }

            Optional<Ticket> holder = ticketRepository.findActiveBySeat(session.getId(), z[1], r[1], c[1]);
            if (holder.isPresent() && holder.get().isOwnedBy(userId)) {
                throw new ConflictException("이미 내 티켓으로 등록된 좌석입니다.", Map.of("code", "MY_TICKET_ALREADY_REGISTERED"));
            }
            if (holder.isEmpty()) {
                throw new BusinessRuleException("SEAT_NOT_REGISTERED", "지금은 비어 있어요. 직접 등록하세요.");
            }
            if (claimRepository.existsPendingBySeat(userId, session.getId(), z[1], r[1], c[1])) {
                throw pendingExists();
            }
            if (claimRepository.countByClaimantIdAndStatus(userId, AdminReviewStatus.PENDING) >= maxPendingPerUser) {
                throw new BusinessRuleException("SEAT_CLAIM_LIMIT_REACHED",
                        "처리를 기다리는 인증 요청은 최대 " + maxPendingPerUser + "건입니다. 처리된 뒤에 다시 요청해주세요.");
            }

            String key = imageStore.store(data);
            deleteFileUnlessCommitted(key);
            try {
                SeatClaim claim = claimRepository.saveAndFlush(SeatClaim.create(userId, session,
                        z[0], z[1], r[0], r[1], c[0], c[1], cleanMemo, key, contentType, data.length));
                return SeatClaimResponse.from(claim);
            } catch (RuntimeException e) {
                imageStore.delete(key);
                throw e;
            }
        });
    }

    private static ConflictException pendingExists() {
        return new ConflictException("같은 좌석에 처리를 기다리는 인증 요청이 이미 있어요.",
                Map.of("code", "SEAT_CLAIM_ALREADY_PENDING"));
    }

    private static byte[] readImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return null;
        }
        if (image.getSize() > SeatClaim.IMAGE_MAX_BYTES) {
            throw new MaxUploadSizeExceededException(SeatClaim.IMAGE_MAX_BYTES);
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다.", e);
        }
    }

    private static String[] normalize(Map<String, String> errors, String field, Supplier<String[]> action) {
        try {
            return action.get();
        } catch (FieldValidationException e) {
            errors.put(field, e.getMessage());
            return null;
        }
    }

    /** 이 트랜잭션이 커밋되지 않고 끝나면(롤백) 방금 저장한 파일을 지운다. */
    private void deleteFileUnlessCommitted(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    imageStore.delete(key);
                }
            }
        });
    }

    public PageResponse<SeatClaimResponse> listMine(Long userId, int page, int size) {
        Pageable pageable = pageable(page, size);
        return transactionTemplate.execute(s -> toPage(claimRepository.findPageByClaimant(userId, pageable), SeatClaimResponse::from));
    }

    /** 본인 또는 관리자만. 그 외·없는 요청은 404, 삭제된 사진은 410. */
    public ImageData image(Long userId, boolean admin, Long claimId) {
        SeatClaim claim = transactionTemplate.execute(s -> claimRepository.findById(claimId).orElse(null));
        if (claim == null || !(admin || claim.isOwnedBy(userId))) {
            throw new NotFoundException(CLAIM_NOT_FOUND_MESSAGE);
        }
        if (claim.isImageDeleted() || !claim.isPending()) {
            // 처리된 요청의 사진은 삭제 대상이다(삭제 실패로 파일이 남아 있어도 더는 보여 주지 않는다)
            throw new ImageDeletedException();
        }
        byte[] bytes = imageStore.read(claim.getImageKey()).orElseThrow(() -> new NotFoundException("사진을 찾을 수 없습니다."));
        return new ImageData(claim.getImageContentType(), bytes);
    }

    // ------------------------------------------------------------------ 관리자

    public PageResponse<AdminSeatClaimResponse> adminList(String status, int page, int size) {
        AdminReviewStatus parsed = parseStatus(status);
        Pageable pageable = pageable(page, size);
        return transactionTemplate.execute(s -> {
            Page<SeatClaim> result = parsed == null ? claimRepository.findPageAll(pageable)
                    : claimRepository.findPageByStatus(parsed, pageable);
            Map<Long, String> nicknames = nicknames(result.getContent());
            return toPage(result, c -> adminResponse(c, nicknames));
        });
    }

    public AdminSeatClaimResponse approve(Long adminId, Long claimId) {
        return review(adminId, claimId, null, true);
    }

    public AdminSeatClaimResponse reject(Long adminId, Long claimId, String note) {
        String cleaned = Performance.cleanDisplayText(note);
        if (cleaned != null && cleaned.length() > SeatClaim.REVIEW_NOTE_MAX_LENGTH) {
            throw new FieldValidationException("note", "사유는 " + SeatClaim.REVIEW_NOTE_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return review(adminId, claimId, cleaned, false);
    }

    private AdminSeatClaimResponse review(Long adminId, Long claimId, String note, boolean approve) {
        return reviewTransactionTemplate.execute(status -> {
            // 인증 요청 행 잠금이 첫 쿼리(잠금 순서의 맨 앞).
            SeatClaim claim = claimRepository.findByIdForUpdate(claimId)
                    .orElseThrow(() -> new NotFoundException(CLAIM_NOT_FOUND_MESSAGE));
            if (!claim.isPending()) {
                throw new ConflictException("이미 처리된 인증 요청입니다.", Map.of("code", "CLAIM_ALREADY_REVIEWED"));
            }
            if (approve && adminId.equals(claim.getClaimantId())) {
                throw new ConflictException("본인이 올린 인증 요청은 직접 승인할 수 없습니다.", Map.of("code", "SELF_REVIEW"));
            }
            LocalDateTime now = sessionTimePolicy.now();
            if (approve) {
                // claim -> 티켓 -> 요청 -> 매칭. 이미 비어 있으면 성공(resolved NULL). 예약 잠금·교환 완료면 409 로 승인 불가.
                // 좌석 키(보조 유니크 인덱스)로 티켓에 FOR UPDATE 를 걸면 '보조 인덱스 -> PK' 순서가 되어, PK 부터 잠그는
                // 티켓 내리기(deactivate)와 교착한다. 그래서 id 만 잠금 없이 읽고 PK 로 잠근 뒤(deactivate 와 같은 순서)
                // 상태는 잠근 행으로 다시 판단한다(deactivateLocked: INACTIVE 면 false, EXCHANGED 면 409).
                Long resolved = ticketRepository.findActiveIdBySeat(claim.getPerformanceSession().getId(),
                                claim.getZoneKey(), claim.getRowKey(), claim.getColKey())
                        .flatMap(ticketRepository::findByIdForUpdate)
                        // 그사이 요청자 본인이 그 좌석을 등록했다면 본인 티켓은 내리지 않는다(승인은 완료, 내려진 티켓 없음)
                        .filter(t -> !t.isOwnedBy(claim.getClaimantId()))
                        .filter(ticketService::deactivateLocked)
                        .map(Ticket::getId).orElse(null);
                claim.approve(adminId, resolved, null, now);
            } else {
                claim.reject(adminId, note, now);
            }
            claimRepository.saveAndFlush(claim);
            deleteImageAfterCommit(claim.getId(), claim.getImageKey());
            return adminResponse(claim, nicknames(List.of(claim)));
        });
    }

    static final String WITHDRAWAL_REJECT_NOTE = "회원 탈퇴로 자동 거절되었습니다.";

    /**
     * 회원 탈퇴: 그 사용자의 PENDING 인증 요청을 모두 거절 처리한다(처리자 = 본인, 사유 고정 문구). 요청 행을 id 오름차순으로 잠그며
     * (잠금 순서의 맨 앞: 인증 요청 -> 티켓 -> 요청 -> 매칭), 사진은 커밋 뒤 삭제한다. 활성 트랜잭션 안에서만 호출한다. 처리한 건수를 돌려준다.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int rejectPendingForWithdrawal(Long userId) {
        LocalDateTime now = sessionTimePolicy.now();
        int rejected = 0;
        for (Long id : claimRepository.findPendingIdsByClaimant(userId)) {
            SeatClaim claim = claimRepository.findByIdForUpdate(id).orElse(null);
            if (claim == null || !claim.isPending()) {
                continue; // 그 사이 관리자가 처리했다
            }
            claim.reject(userId, WITHDRAWAL_REJECT_NOTE, now);
            claimRepository.saveAndFlush(claim);
            deleteImageAfterCommit(claim.getId(), claim.getImageKey());
            rejected++;
        }
        return rejected;
    }

    /** 커밋 뒤에 파일 삭제 -> 성공하면 별도 짧은 트랜잭션에서 image_deleted_at 기록. 어느 단계가 실패해도 처리 결과는 유지(로그만). */
    private void deleteImageAfterCommit(Long claimId, String imageKey) {
        Runnable task = () -> {
            try {
                if (!imageStore.delete(imageKey)) {
                    return;
                }
                newTransactionTemplate.executeWithoutResult(s -> claimRepository.findById(claimId).ifPresent(c -> {
                    c.markImageDeleted(sessionTimePolicy.now());
                    claimRepository.save(c);
                }));
            } catch (RuntimeException e) {
                log.warn("Seat claim image cleanup failed (claim={}): {}", claimId, e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    // ------------------------------------------------------------------ 변환 도우미

    private AdminSeatClaimResponse adminResponse(SeatClaim c, Map<Long, String> nicknames) {
        Holder holder = ticketRepository.findLatestBySeat(c.getPerformanceSession().getId(),
                        c.getZoneKey(), c.getRowKey(), c.getColKey(), PageRequest.of(0, 1))
                .stream().findFirst().map(this::holder).orElse(null);
        return AdminSeatClaimResponse.of(c, nicknames.get(c.getClaimantId()),
                c.getReviewedById() == null ? null : nicknames.get(c.getReviewedById()), holder);
    }

    private Holder holder(Ticket t) {
        String status = t.getStatus() == TicketStatus.ACTIVE && lockRepository.existsByTicketId(t.getId())
                ? "RESERVED_LOCKED" : t.getStatus().name();
        return new Holder(status, t.getUser().getNickname());
    }

    private Map<Long, String> nicknames(List<SeatClaim> claims) {
        Set<Long> ids = claims.stream().flatMap(c -> java.util.stream.Stream.of(c.getClaimantId(), c.getReviewedById()))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> result = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            result.put(u.getId(), u.getNickname());
        }
        return result;
    }

    private static AdminReviewStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AdminReviewStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("status", "status는 PENDING, APPROVED, REJECTED 중 하나여야 합니다.");
        }
    }

    private static Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }

    private static <E, T> PageResponse<T> toPage(Page<E> page, java.util.function.Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
