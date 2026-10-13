package com.seatswap.service;

import com.seatswap.domain.Ticket;
import com.seatswap.domain.User;
import com.seatswap.domain.UserRole;
import com.seatswap.dto.request.WithdrawRequest;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.SeatSwapException;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.NotificationRepository;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.websocket.WebSocketSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 회원 탈퇴(FR-01). 물리 삭제가 아니라 익명화다: 진행 중인 교환을 모두 시스템 취소하고 티켓을 내린 뒤 users 행을 익명화한다.
 * 채팅·읽음 상태·교환 이력·차단·신고·제재는 지우지 않는다(FK 유지, 닉네임 표기가 '탈퇴한 회원'이 된다).
 *
 * <p>본인 확인(트랜잭션 밖, BCrypt 가 느리므로 잠금을 잡기 전에 한다): 비밀번호 로그인 계정은 비밀번호, 소셜 전용 계정은 "탈퇴합니다".
 * 사용자당 분당 5회(429 WITHDRAW_RATE_LIMITED). ADMIN 은 409 ADMIN_CANNOT_WITHDRAW.
 *
 * <p>트랜잭션(READ_COMMITTED; OpenMatchCanceller 호출 규칙) 잠금 순서:
 * 인증 요청 행(id↑) -> 티켓 id↑ -> 요청 -> 매칭 id↑ -> users 행 -> (알림, 맨 끝).
 * spec 의 'users 행 FOR UPDATE 가 맨 앞' 은 따르지 않는다: 제안(propose)은 티켓을 잠근 뒤 exchange_match 를 INSERT 하면서 users 행에
 * FK 공유 잠금을 요청하므로, users 행을 먼저 X 로 잡고 티켓을 잠그면 제안과 교착한다(이용 정지도 users 잠금 없이 취소한다).
 * 대신 users 잠금을 정리 뒤에 잡고 그 안에서 정리를 한 번 더 한다: 사용자 단위로 티켓·인증 요청·제안을 만드는 경로(TicketService.create,
 * SeatClaimService.create 등)는 모두 users 행 FOR UPDATE 로 시작하므로, users 잠금을 잡은 뒤에는 새 활성 티켓·PENDING 인증 요청이 생길 수 없다.
 * 탈퇴 끼리의 직렬화도 users 잠금이 한다(뒤에 온 쪽은 잠금 뒤 withdrawn_at 을 보고 401 로 끝난다).
 * 요청(exchange_request) 갱신이 매칭 취소보다 뒤에 오는 점(티켓 -> 요청 -> 매칭 의 부분 순서에서 요청과 매칭이 뒤바뀜)은
 * 본인 요청을 동시에 수정·삭제하는 경로와만 겹치는 좁은 창이며, 교착 감지기가 한쪽을 되돌린다(503).
 * 알림 삭제는 맨 끝이고(알림 행을 잠근 뒤 다른 잠금 금지) 상대에게 가는 알림은 만들지 않는다.
 *
 * <p>커밋 뒤: 그 사용자의 열린 STOMP 연결을 모두 닫는다(실패해도 결과 유지).
 */
@Slf4j
@Service
public class WithdrawService {

    public static final String CONFIRM_TEXT = "탈퇴합니다";
    static final String PASSWORD_MISMATCH_MESSAGE = "비밀번호가 올바르지 않습니다.";
    static final String CONFIRM_MISMATCH_MESSAGE = "확인 문구가 일치하지 않습니다. '" + CONFIRM_TEXT + "'를 정확히 입력해주세요.";
    static final String ADMIN_MESSAGE = "관리자 계정은 탈퇴할 수 없습니다. 관리자 권한을 해제한 뒤 탈퇴해주세요.";
    static final int MAX_ATTEMPTS_PER_MINUTE = 5;
    private static final long WINDOW_MILLIS = 60_000;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int NOTIFICATION_CHUNK = 500;

    /** 429 WITHDRAW_RATE_LIMITED. 응답 {code, message, retryAfterSeconds} 와 Retry-After 헤더는 GlobalExceptionHandler 가 만든다. */
    public static class WithdrawRateLimitedException extends SeatSwapException {
        private final long retryAfterSeconds;

        public WithdrawRateLimitedException(long retryAfterSeconds) {
            super("탈퇴 시도가 너무 많아요. 잠시 후 다시 시도해주세요.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return "WITHDRAW_RATE_LIMITED";
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final ExchangeRequestRepository exchangeRequestRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final NotificationRepository notificationRepository;
    private final OpenMatchCanceller matchCanceller;
    private final SeatClaimService seatClaimService;
    private final WebSocketSessionRegistry sessionRegistry;
    private final PasswordEncoder passwordEncoder;
    private final SessionTimePolicy timePolicy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Long, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();

    public WithdrawService(UserRepository userRepository, TicketRepository ticketRepository,
                           ExchangeRequestRepository exchangeRequestRepository,
                           SocialAccountRepository socialAccountRepository,
                           NotificationRepository notificationRepository,
                           OpenMatchCanceller matchCanceller, SeatClaimService seatClaimService,
                           WebSocketSessionRegistry sessionRegistry, PasswordEncoder passwordEncoder,
                           SessionTimePolicy timePolicy, Clock clock, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.exchangeRequestRepository = exchangeRequestRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.notificationRepository = notificationRepository;
        this.matchCanceller = matchCanceller;
        this.seatClaimService = seatClaimService;
        this.sessionRegistry = sessionRegistry;
        this.passwordEncoder = passwordEncoder;
        this.timePolicy = timePolicy;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void withdraw(Long userId, WithdrawRequest request) {
        acquireAttempt(userId);

        User current = userRepository.findById(userId)
                .filter(u -> !u.isWithdrawn())
                .orElseThrow(WithdrawService::unauthenticated);
        if (current.getRole() == UserRole.ADMIN) {
            throw adminCannotWithdraw();
        }
        verifyIdentity(current, request);

        // BCrypt 는 느리므로 잠금을 잡기 전에 계산한다. 아무도 모르는 무작위 값의 해시라 비밀번호 로그인은 불가능하다.
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String unknownPassword = passwordEncoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));

        transactionTemplate.executeWithoutResult(status -> {
            cleanUp(userId);   // 1차: 잠금 순서대로 열린 매칭·티켓·인증 요청 정리
            User user = userRepository.findByIdForUpdate(userId)
                    .filter(u -> !u.isWithdrawn())   // 동시에 들어온 다른 탈퇴가 먼저 끝났다
                    .orElseThrow(WithdrawService::unauthenticated);
            if (user.getRole() == UserRole.ADMIN) {   // 확인 뒤 관리자로 승격된 경우(롤백)
                throw adminCannotWithdraw();
            }
            cleanUp(userId);   // 2차: users 잠금 뒤에는 새 티켓·인증 요청이 생기지 않는다. 그 사이 생긴 것을 마저 정리(보통 0건)

            socialAccountRepository.deleteAllByUserId(userId);
            user.anonymizeForWithdrawal(unknownPassword, timePolicy.now());
            userRepository.saveAndFlush(user);

            deleteNotificationsOf(userId);   // 맨 끝
            closeSocketsAfterCommit(userId);
        });
        log.info("Withdrawal completed (userId={})", userId);
    }

    /** 인증 요청 거절 -> 열린 매칭 시스템 취소 -> 활성 티켓의 요청 CLOSED + 티켓 INACTIVE. 멱등이다. */
    private void cleanUp(Long userId) {
        seatClaimService.rejectPendingForWithdrawal(userId);
        // 열린 매칭 취소: 내 활성 티켓과 상대 티켓을 id 오름차순으로 잠근 뒤 매칭 id 오름차순으로 잠가 취소하고 예약 잠금을 푼다.
        matchCanceller.cancelAllOf(userId);
        LocalDateTime now = timePolicy.now();
        // 매칭이 모두 닫혔으니 예약 잠금이 없어 TICKET_RESERVED 로 막힐 일이 없다. 이미 잠긴 티켓을 PK 로 다시 확인한다.
        for (Long ticketId : ticketRepository.findActiveIdsByUserIds(List.of(userId)).stream().sorted().toList()) {
            ticketRepository.findByIdForUpdate(ticketId).filter(Ticket::isActive).ifPresent(ticket -> {
                exchangeRequestRepository.closeByTicketId(ticketId, now);   // OPEN -> CLOSED (DELETED 는 그대로)
                ticket.deactivate();
                ticketRepository.saveAndFlush(ticket);
            });
        }
    }

    private void deleteNotificationsOf(Long userId) {
        List<Long> ids = notificationRepository.findAllIdsByUserId(userId);   // 잠금 없는 SELECT
        for (int from = 0; from < ids.size(); from += NOTIFICATION_CHUNK) {
            notificationRepository.deleteAllByIds(ids.subList(from, Math.min(ids.size(), from + NOTIFICATION_CHUNK)), userId);
        }
    }

    /** 커밋된 뒤에만 연결을 닫는다(롤백이면 닫지 않는다). 실패해도 탈퇴 결과에는 영향이 없다. */
    private void closeSocketsAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sessionRegistry.closeAllOf(userId);
                } catch (RuntimeException e) {
                    // 토큰은 이미 무효(401)라 연결이 남아도 구독만 하고 아무것도 못 한다. 토큰 만료 때 정리된다.
                }
            }
        });
    }

    private void verifyIdentity(User user, WithdrawRequest request) {
        if (user.isPasswordLoginEnabled()) {
            String password = request == null ? null : request.password();
            if (password == null || password.isEmpty()
                    || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES   // 가입 단계에서 거부되는 길이: 일치할 수 없다
                    || !passwordEncoder.matches(password, user.getPassword())) {
                throw new SeatSwapException(PASSWORD_MISMATCH_MESSAGE);
            }
            return;
        }
        String confirmText = request == null ? null : request.confirmText();
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw new FieldValidationException("confirmText", CONFIRM_MISMATCH_MESSAGE);
        }
    }

    /** 사용자당 분당 5회(시도마다 센다. 성공하면 계정이 사라지므로 성공 후 초기화는 필요 없다). 메모리 슬라이딩 윈도. */
    private void acquireAttempt(Long userId) {
        long now = clock.millis();
        ArrayDeque<Long> hits = attempts.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MILLIS) {
                hits.pollFirst();
            }
            if (hits.size() >= MAX_ATTEMPTS_PER_MINUTE) {
                long wait = hits.peekFirst() + WINDOW_MILLIS - now;
                throw new WithdrawRateLimitedException(Math.max(1, (wait + 999) / 1000));
            }
            hits.addLast(now);
        }
        if (attempts.size() > 10_000) {   // 오래 쉰 키를 가끔 쓸어 낸다
            attempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() > 2 * WINDOW_MILLIS;
                }
            });
        }
    }

    /** 테스트 전용: 속도 제한 기록을 비운다(테스트는 DB 를 비워 사용자 id 를 재사용한다). */
    void clearRateLimits() {
        attempts.clear();
    }

    private static InsufficientAuthenticationException unauthenticated() {
        return new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다.");
    }

    private static ConflictException adminCannotWithdraw() {
        return new ConflictException(ADMIN_MESSAGE, Map.of("code", "ADMIN_CANNOT_WITHDRAW"));
    }
}
