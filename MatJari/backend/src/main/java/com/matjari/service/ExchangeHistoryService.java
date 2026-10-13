package com.matjari.service;

import com.matjari.domain.ExchangeHistory;
import com.matjari.dto.response.ExchangeHistoryResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.repository.ExchangeHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 교환 이력 조회(읽기 전용, 본인 행만, id 내림차순 = 최신 교환 먼저). 이력은 append-only 스냅샷이라 잠금이 필요 없다. */
@Service
public class ExchangeHistoryService {

    static final int MAX_PAGE_SIZE = 100;

    private final ExchangeHistoryRepository historyRepository;

    public ExchangeHistoryService(ExchangeHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExchangeHistoryResponse> listMine(Long userId, int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        Page<ExchangeHistory> result = historyRepository.findByUserIdOrderByIdDesc(userId,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return new PageResponse<>(result.getContent().stream().map(ExchangeHistoryResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
