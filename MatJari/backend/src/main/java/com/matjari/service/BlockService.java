package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.domain.UserBlock;
import com.matjari.dto.response.BlockDtos.BlockItem;
import com.matjari.dto.response.BlockDtos.BlockResult;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeMatchRepository;
import com.matjari.repository.UserBlockRepository;
import com.matjari.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 사용자 차단. 방향은 하나(차단한 사람 -> 차단당한 사람)이고 효과는 양방향이다(후보 제외는 ExchangeCandidateRepository).
 * 차단은 매칭 화면에서만 시작하며 상대는 매칭에서 정해진다(참여자만, 비참여자·없는 매칭은 404).
 *
 * 차단 트랜잭션(READ_COMMITTED): 사용자 행은 잠그지 않는다 -> {@link OpenMatchCanceller#cancelBetween}(티켓 id↑ -> 매칭 id↑ 잠금)
 * -> user_block INSERT 는 맨 마지막. 이미 차단 중이면 아무것도 쓰지 않고 같은 결과(alreadyBlocked)를 돌려준다(멱등).
 * 사용자당 상한 200(422 BLOCK_LIMIT_REACHED)은 사용자 행 잠금이 없어 같은 사용자의 동시 차단에서는 근소하게 넘을 수 있다(알려진 한계).
 * 차단당한 쪽에는 알림·표시를 하지 않는다. 해제는 행 삭제이며 취소된 매칭은 되살아나지 않는다.
 */
@Service
@Slf4j
public class BlockService {

    static final int MAX_PAGE_SIZE = 100;
    static final String MATCH_NOT_FOUND_MESSAGE = ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE;
    static final String BLOCK_NOT_FOUND_MESSAGE = "차단 내역을 찾을 수 없습니다.";
    static final String LIMIT_CODE = "BLOCK_LIMIT_REACHED";
    static final String LIMIT_MESSAGE = "차단은 최대 " + UserBlock.MAX_PER_USER + "명까지 할 수 있어요. 차단 목록에서 해제한 뒤 다시 시도해주세요.";

    private final ExchangeMatchRepository matchRepository;
    private final UserBlockRepository blockRepository;
    private final UserRepository userRepository;
    private final OpenMatchCanceller canceller;
    private final TransactionTemplate tx;

    public BlockService(ExchangeMatchRepository matchRepository, UserBlockRepository blockRepository,
                        UserRepository userRepository, OpenMatchCanceller canceller,
                        PlatformTransactionManager transactionManager) {
        this.matchRepository = matchRepository;
        this.blockRepository = blockRepository;
        this.userRepository = userRepository;
        this.canceller = canceller;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public BlockResult blockFromMatch(Long userId, Long matchId) {
        List<Object[]> rows = matchRepository.findParticipants(matchId);
        if (rows.isEmpty()) {
            throw new NotFoundException(MATCH_NOT_FOUND_MESSAGE);
        }
        Long a = (Long) rows.get(0)[0];
        Long b = (Long) rows.get(0)[1];
        if (!userId.equals(a) && !userId.equals(b)) {
            throw new NotFoundException(MATCH_NOT_FOUND_MESSAGE);   // 비참여자에게는 없는 매칭과 같은 404
        }
        Long otherId = userId.equals(a) ? b : a;
        String nickname = userRepository.findById(otherId).map(User::getNickname)
                .orElseThrow(() -> new NotFoundException(MATCH_NOT_FOUND_MESSAGE));

        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> {
                    var existing = blockRepository.findByBlockerIdAndBlockedId(userId, otherId);
                    if (existing.isPresent()) {
                        return new BlockResult(existing.get().getId(), nickname, 0, true);
                    }
                    long count = blockRepository.countByBlockerId(userId);
                    if (count >= UserBlock.MAX_PER_USER) {
                        throw new BusinessRuleException(LIMIT_CODE, LIMIT_MESSAGE,
                                Map.of("count", count, "limit", UserBlock.MAX_PER_USER));
                    }
                    int canceled = canceller.cancelBetween(userId, otherId);
                    UserBlock saved = blockRepository.saveAndFlush(UserBlock.of(userId, otherId));   // 맨 마지막 쓰기
                    return new BlockResult(saved.getId(), nickname, canceled, false);
                });
            } catch (DataIntegrityViolationException e) {
                // 같은 쌍의 동시 차단: 한 번 다시 시도하면 기존 행을 보고 멱등 응답이 된다.
                if (attempt >= 1 || !DataIntegrityViolations.isViolationOf(e, UserBlock.UNIQUE_PAIR)) {
                    throw e;
                }
                log.info("Block race on pair ({}, {}), retrying", userId, otherId);
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BlockItem> listMine(Long userId, int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        Page<Object[]> result = blockRepository.findListByBlockerId(userId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        List<BlockItem> content = result.getContent().stream()
                .map(r -> new BlockItem((Long) r[0], (String) r[1], (LocalDateTime) r[2])).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** 내 차단 해제. 남의 것·없는 것은 404(멱등 아님). 취소된 매칭은 되살리지 않는다. */
    @Transactional
    public void unblock(Long userId, Long blockId) {
        UserBlock block = blockRepository.findByIdAndBlockerId(blockId, userId)
                .orElseThrow(() -> new NotFoundException(BLOCK_NOT_FOUND_MESSAGE));
        blockRepository.delete(block);
    }
}
