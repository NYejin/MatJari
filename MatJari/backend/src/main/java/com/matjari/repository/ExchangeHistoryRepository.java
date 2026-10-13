package com.matjari.repository;

import com.matjari.domain.ExchangeHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 교환 이력(append-only). 완료 시 INSERT, 마이페이지 조회(본인 행만). */
public interface ExchangeHistoryRepository extends JpaRepository<ExchangeHistory, Long> {

    List<ExchangeHistory> findByMatchIdOrderByIdAsc(Long matchId);

    /** 내 이력 최신순(idx_exchange_history_user 로 사용자 행만 읽는다). */
    Page<ExchangeHistory> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
