package com.matjari.service;

import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeMatchQueryRepository;
import com.matjari.repository.ExchangeMatchQueryRepository.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 내 매칭 목록·단건 조회 (읽기 전용, 잠금 없음). 비참여자는 없는 매칭과 같은 404. */
@Service
@RequiredArgsConstructor
public class ExchangeMatchQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final ExchangeMatchQueryRepository queryRepository;

    @Transactional(readOnly = true)
    public PageResponse<ExchangeMatchResponse> findMine(Long userId, String role, List<String> status, int page, int size) {
        Role roleFilter = parseRole(role);
        List<String> statuses = parseStatuses(status);
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);
        long total = queryRepository.countMine(userId, roleFilter, statuses);
        List<ExchangeMatchResponse> content = total == 0 ? List.of()
                : queryRepository.findMine(userId, roleFilter, statuses, pageSize, (long) page * pageSize).stream()
                        .map(r -> r.toResponse(userId)).toList();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }

    @Transactional(readOnly = true)
    public ExchangeMatchResponse findOne(Long userId, Long matchId) {
        return queryRepository.findOne(matchId, userId).map(r -> r.toResponse(userId))
                .orElseThrow(() -> new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE));
    }

    private static Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            return Role.ALL;
        }
        try {
            return Role.valueOf(role.trim());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("role", "role은 SENT, RECEIVED, ALL 중 하나여야 합니다.");
        }
    }

    /** 반복(status=a&status=b)과 쉼표 구분 모두 허용하고 중복은 제거한다. */
    private static List<String> parseStatuses(List<String> raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String item : raw) {
            for (String token : item.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String name;
                try {
                    name = ExchangeMatchStatus.valueOf(t).name();
                } catch (IllegalArgumentException e) {
                    throw new FieldValidationException("status",
                            "status는 CHATTING, RESERVED, COMPLETED, CANCELED 중 하나여야 합니다.");
                }
                if (!result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }
}
