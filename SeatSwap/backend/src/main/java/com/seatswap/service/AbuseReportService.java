package com.seatswap.service;

import com.seatswap.domain.AbuseReport;
import com.seatswap.domain.ExchangeMatch;
import com.seatswap.domain.User;
import com.seatswap.dto.response.ModerationDtos.MyReportResponse;
import com.seatswap.dto.response.ModerationDtos.ReportCreateRequest;
import com.seatswap.dto.response.ModerationDtos.ReportCreatedResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.AbuseReportRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 신고 (V13). 매칭 화면에서 참여자만(비참여자·없는 매칭 404) 매칭 상대를 신고한다. 닫힌(CANCELED·COMPLETED) 매칭에서도 가능하다.
 * 신고는 대상에게 알리지 않고 자동 제재도 없다. 알림 행을 만들지 않는다.
 *
 * <h3>순서·잠금</h3>
 * ①입력 검증(400 {reason|detail}) ②<b>트랜잭션 밖</b>에서 매칭을 읽어 참여자 확인 ③짧은 트랜잭션에서 중복 확인(409 REPORT_ALREADY_OPEN)
 * -> 사용자당 OPEN 상한 확인(422 REPORT_LIMIT_REACHED) -> <b>맨 끝에서</b> INSERT. 어떤 행도 잠그지 않는다: users 행을 X 로 잠그면
 * (INSERT 의 FK 검사가 매칭 행 S 잠금을 요구하는데) 매칭 행을 X 로 쥐고 users S 를 기다리는 기존 경로(예약 취소의 reserved_by_id 갱신)와
 * 교착할 수 있어서다. 그래서 같은 (신고자, 매칭)의 OPEN 중복은 DB 유니크(uk_abuse_report_open)가 막고(409로 변환),
 * 사용자당 OPEN 상한 10건은 동시 요청이 겹치면 몇 건 넘을 수 있는 느슨한 한도다(남용 방지용).
 */
@Service
public class AbuseReportService {

    static final String REPORT_NOT_FOUND_MESSAGE = "신고를 찾을 수 없습니다.";
    static final int MAX_PAGE_SIZE = 100;

    private final ExchangeMatchRepository matchRepository;
    private final AbuseReportRepository reportRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate tx;

    public AbuseReportService(ExchangeMatchRepository matchRepository,
                              AbuseReportRepository reportRepository,
                              UserRepository userRepository,
                              PlatformTransactionManager transactionManager) {
        this.matchRepository = matchRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public ReportCreatedResponse create(Long userId, Long matchId, ReportCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        AbuseReport.Reason reason = parseReason(request == null ? null : request.reason(), errors);
        String detail = cleanDetail(request == null ? null : request.detail(), errors);
        if (reason == AbuseReport.Reason.OTHER && detail == null && !errors.containsKey("detail")) {
            errors.put("detail", "기타 사유는 상세 내용을 입력해주세요.");
        }
        if (!errors.isEmpty()) {
            throw FieldValidationException.ofAll(errors);
        }

        ExchangeMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE));
        Long reportedId;
        if (userId.equals(match.getUserAId())) {
            reportedId = match.getUserBId();
        } else if (userId.equals(match.getUserBId())) {
            reportedId = match.getUserAId();
        } else {
            throw new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE);
        }

        AbuseReport saved = tx.execute(status -> {
            if (reportRepository.existsByReporterIdAndMatchIdAndStatus(userId, matchId, AbuseReport.Status.OPEN)) {
                throw alreadyOpen();
            }
            if (reportRepository.countByReporterIdAndStatus(userId, AbuseReport.Status.OPEN) >= AbuseReport.MAX_OPEN_PER_USER) {
                throw new BusinessRuleException("REPORT_LIMIT_REACHED",
                        "처리 중인 신고가 너무 많아요. 처리가 끝난 뒤에 다시 신고해주세요.",
                        Map.of("limit", AbuseReport.MAX_OPEN_PER_USER));
            }
            try {
                // 트랜잭션의 마지막 쓰기. 즉시 flush 해 유니크 위반을 여기서 분류한다.
                return reportRepository.saveAndFlush(AbuseReport.create(userId, reportedId, matchId, reason, detail));
            } catch (DataIntegrityViolationException e) {
                if (DataIntegrityViolations.isViolationOf(e, AbuseReport.UNIQUE_OPEN)) {
                    throw alreadyOpen();
                }
                throw e;
            }
        });
        return new ReportCreatedResponse(saved.getId(), saved.getStatus().name(), saved.getCreatedAt());
    }

    /** 내 신고 내역(최신순). 처리 상태와 처리 시각만 보이고 메모·제재 내용은 보이지 않는다. */
    public PageResponse<MyReportResponse> listMine(Long userId, int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        return tx.execute(status -> {
            Page<AbuseReport> result = reportRepository.findByReporterIdOrderByIdDesc(userId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
            Set<Long> ids = result.getContent().stream().map(AbuseReport::getReportedId).collect(Collectors.toSet());
            Map<Long, String> nicknames = new HashMap<>();
            for (User u : userRepository.findAllById(ids)) {
                nicknames.put(u.getId(), u.getNickname());
            }
            List<MyReportResponse> content = result.getContent().stream()
                    .map(r -> new MyReportResponse(r.getId(), r.getMatchId(), nicknames.get(r.getReportedId()), r.getReason().name(),
                            r.getStatus().name(), r.getCreatedAt(), r.getHandledAt()))
                    .toList();
            return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
        });
    }

    private static ConflictException alreadyOpen() {
        return new ConflictException("이미 이 채팅에 접수한 신고가 처리 중이에요.", Map.of("code", "REPORT_ALREADY_OPEN"));
    }

    private static AbuseReport.Reason parseReason(String value, Map<String, String> errors) {
        if (value != null) {
            try {
                return AbuseReport.Reason.valueOf(value.strip());
            } catch (IllegalArgumentException ignored) {
                // 아래에서 오류로 기록
            }
        }
        errors.put("reason", "신고 사유를 선택해주세요.");
        return null;
    }

    /** 앞뒤 공백 제거, 비면 null, 500자(코드 포인트) 초과는 오류. */
    private static String cleanDetail(String value, Map<String, String> errors) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > AbuseReport.DETAIL_MAX_LENGTH) {
            errors.put("detail", "상세 내용은 " + AbuseReport.DETAIL_MAX_LENGTH + "자 이하로 입력해주세요.");
            return null;
        }
        return stripped;
    }
}
