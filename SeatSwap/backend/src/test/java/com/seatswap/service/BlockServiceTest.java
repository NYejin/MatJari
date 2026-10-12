package com.seatswap.service;

import com.seatswap.domain.User;
import com.seatswap.domain.UserBlock;
import com.seatswap.dto.response.BlockDtos.BlockResult;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.UserBlockRepository;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlockServiceTest {

    private ExchangeMatchRepository matchRepository;
    private UserBlockRepository blockRepository;
    private OpenMatchCanceller canceller;
    private BlockService service;

    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        blockRepository = mock(UserBlockRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        canceller = mock(OpenMatchCanceller.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new BlockService(matchRepository, blockRepository, userRepository, canceller, tm);

        when(matchRepository.findParticipants(5L)).thenReturn(Collections.singletonList(new Object[]{1L, 2L}));
        User other = User.create("o@t.com", "x", "상대");
        when(userRepository.findById(2L)).thenReturn(Optional.of(other));
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.create("m@t.com", "x", "나")));
    }

    private static UserBlock saved(long id, long blocker, long blocked) {
        UserBlock b = UserBlock.of(blocker, blocked);
        ReflectionTestUtils.setField(b, "id", id);
        return b;
    }

    @Test
    void nonParticipantAndMissingMatchGetTheSame404() {
        assertThatThrownBy(() -> service.blockFromMatch(3L, 5L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.blockFromMatch(1L, 99L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(canceller);
        verify(blockRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelsOpenMatchesBeforeInsertingTheBlockLast() {
        when(blockRepository.findByBlockerIdAndBlockedId(1L, 2L)).thenReturn(Optional.empty());
        when(blockRepository.countByBlockerId(1L)).thenReturn(3L);
        when(canceller.cancelBetween(1L, 2L)).thenReturn(2);
        when(blockRepository.saveAndFlush(any(UserBlock.class))).thenReturn(saved(7L, 1L, 2L));

        BlockResult r = service.blockFromMatch(1L, 5L);

        assertThat(r).isEqualTo(new BlockResult(7L, "상대", 2, false));
        InOrder order = inOrder(canceller, blockRepository);
        order.verify(canceller).cancelBetween(1L, 2L);
        order.verify(blockRepository).saveAndFlush(any(UserBlock.class));
    }

    @Test
    void secondCallIsIdempotentAndWritesNothing() {
        when(blockRepository.findByBlockerIdAndBlockedId(1L, 2L)).thenReturn(Optional.of(saved(7L, 1L, 2L)));
        assertThat(service.blockFromMatch(1L, 5L)).isEqualTo(new BlockResult(7L, "상대", 0, true));
        verifyNoInteractions(canceller);
        verify(blockRepository, never()).saveAndFlush(any());
    }

    @Test
    void limitIs200() {
        when(blockRepository.findByBlockerIdAndBlockedId(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(blockRepository.countByBlockerId(1L)).thenReturn(200L);
        assertThatThrownBy(() -> service.blockFromMatch(1L, 5L))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("BLOCK_LIMIT_REACHED"));
        verifyNoInteractions(canceller);
    }
}
