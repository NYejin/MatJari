package com.matjari.service;

import com.matjari.repository.CandidateNoticeRepository;
import com.matjari.repository.ExchangeCandidateRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 새 교환 후보 알림(NEW_CANDIDATE, V29). 교환 요청 R 이 생성되거나 희망 조건이 수정되어 <b>커밋된 뒤</b>, 후보 관계가 대칭이라는 점을 써서
 * R 의 후보 목록(기존 후보 SQL 재사용, 최대 {@value #MAX_CANDIDATES}건)에 든 각 요청 R' 의 소유자에게 "R' 에 새 후보가 생겼다"를 알린다.
 * R 의 소유자 본인에게는 보내지 않는다.
 *
 * <p>요청 생성·수정 트랜잭션의 잠금 순서(티켓 -> 요청 -> 매칭)를 건드리지 않으려고 커밋 뒤({@link TransactionPhase#AFTER_COMMIT}) 이벤트로 받아
 * 별도 스레드·별도 트랜잭션(REQUIRES_NEW)에서 처리한다. 여기서는 티켓·요청·매칭 행을 FOR UPDATE 로 잡지 않고 일반 읽기만 한다.
 * 실패는 로그만 남기고 삼킨다(요청 저장은 이미 커밋되어 유지된다). 쌍마다 트랜잭션이 따로라 한 쌍이 실패(교착 희생자 등)해도 나머지는 계속하고,
 * 실패한 쌍은 candidate_notice 가 롤백되어 다음 변경 때 다시 시도된다.
 *
 * <p>중복 방지: (R', R) 쌍을 candidate_notice 에 INSERT IGNORE 해서 새로 들어간 때만(영향 행 1) 알림을 쓴다. 알림은 (R' 소유자, R')당 행 1개를
 * 재사용한다(PK 먼저 갱신 -> 없을 때만 upsert, NotificationService.notifyReusable). 알림 쓰기는 이 짧은 트랜잭션의 마지막이다.
 *
 * <p>R 이 열려 있지 않거나(닫힘·삭제), 티켓을 내렸거나, 예약 잠금 중이거나, 공연이 숨김이거나, R 의 소유자가 이용 정지 중이면 아무것도 보내지 않는다
 * (R' 쪽에서 R 이 후보로 보이지 않는 경우다). 차단·정지된 상대·같은 쌍 열린 매칭은 후보 SQL 이 이미 거른다.
 * 처리는 단일 스레드·유한 큐다(큐가 차면 그 이벤트는 버리고 로그만 남긴다 - 알림은 부가 기능이다).
 */
@Slf4j
@Component
public class NewCandidateNotifier {

    static final int MAX_CANDIDATES = 100;
    private static final int QUEUE_CAPACITY = 200;

    private final ExchangeCandidateRepository candidateRepository;
    private final CandidateNoticeRepository noticeRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;

    @Autowired
    public NewCandidateNotifier(ExchangeCandidateRepository candidateRepository, CandidateNoticeRepository noticeRepository,
                                NotificationService notificationService, PlatformTransactionManager transactionManager, Clock clock) {
        this(candidateRepository, noticeRepository, notificationService, transactionManager, clock, null);
    }

    /** 테스트용: executor 를 주면(예: 호출 스레드 실행기) 그것을 쓴다. */
    NewCandidateNotifier(ExchangeCandidateRepository candidateRepository, CandidateNoticeRepository noticeRepository,
                         NotificationService notificationService, PlatformTransactionManager transactionManager, Clock clock,
                         Executor executor) {
        this.candidateRepository = candidateRepository;
        this.noticeRepository = noticeRepository;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        if (executor == null) {
            this.ownedExecutor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
                Thread t = new Thread(r, "new-candidate-notifier");
                t.setDaemon(true);
                return t;
            });
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executor;
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    /** 요청 생성·수정이 커밋된 뒤에만 불린다(롤백이면 이벤트가 버려진다). 실제 처리는 다른 스레드에서 한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequestChanged(NewCandidateCheckEvent event) {
        try {
            executor.execute(() -> notifyCandidatesOf(event.requestId()));
        } catch (RejectedExecutionException e) {
            log.warn("새 후보 알림 대기열이 가득 차 건너뜁니다 (requestId={})", event.requestId());
        }
    }

    /** 요청 R 의 후보 목록에 든 각 요청의 소유자에게 새 후보 알림을 쓴다. 어떤 예외도 밖으로 던지지 않는다. 알린 건수를 돌려준다. */
    int notifyCandidatesOf(Long requestId) {
        try {
            return doNotify(requestId);
        } catch (RuntimeException e) {
            log.warn("새 후보 알림 처리 실패 (requestId={}): {}", requestId, e.toString());
            return 0;
        }
    }

    private int doNotify(Long requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long ownerId = noticeRepository.findEligibleOwner(requestId, now);
        if (ownerId == null) {
            return 0;
        }
        List<ExchangeCandidateRepository.Row> rows =
                candidateRepository.findCandidates(requestId, now.toLocalDate().atStartOfDay(), MAX_CANDIDATES, 0);
        if (rows.isEmpty()) {
            return 0;
        }
        Map<Long, Long> ownerByTicket = noticeRepository.ticketOwners(rows.stream().map(ExchangeCandidateRepository.Row::ticketId).toList());
        int notified = 0;
        for (ExchangeCandidateRepository.Row row : rows) {
            Long recipient = ownerByTicket.get(row.ticketId());
            if (recipient == null || recipient.equals(ownerId)) {
                continue;   // R 의 소유자 본인에게는 보내지 않는다
            }
            try {
                Boolean wrote = tx.execute(status -> {
                    if (noticeRepository.insertIfAbsent(row.requestId(), requestId, now) == 0) {
                        return false;   // 이미 알린 쌍
                    }
                    notificationService.notifyNewCandidate(recipient, row.requestId());   // 이 짧은 트랜잭션의 마지막 쓰기
                    return true;
                });
                if (Boolean.TRUE.equals(wrote)) {
                    notified++;
                }
            } catch (RuntimeException e) {
                log.warn("새 후보 알림 쓰기 실패 (requestId={}, recipientRequestId={}): {}", requestId, row.requestId(), e.toString());
            }
        }
        log.info("새 후보 알림 처리 (requestId={}, 후보={}, 알림={})", requestId, rows.size(), notified);
        return notified;
    }
}
