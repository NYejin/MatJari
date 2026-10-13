package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import com.matjari.repository.ExchangeHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeHistoryServiceTest {

    private final ExchangeHistoryRepository repository = mock(ExchangeHistoryRepository.class);
    private final ExchangeHistoryService service = new ExchangeHistoryService(repository);

    @Test
    void rejectsBadPageAndSizeWithoutQuerying() {
        assertThatThrownBy(() -> service.listMine(1L, -1, 20)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.listMine(1L, 0, 0)).isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void capsSizeAndQueriesOnlyTheCallersRows() {
        Page<com.matjari.domain.ExchangeHistory> empty = new PageImpl<>(List.of());
        when(repository.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class))).thenReturn(empty);

        assertThat(service.listMine(7L, 1, 1000).content()).isEmpty();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdOrderByIdDesc(eq(7L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
    }
}
