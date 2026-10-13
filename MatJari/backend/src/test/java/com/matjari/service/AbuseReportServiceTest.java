package com.matjari.service;

import com.matjari.domain.AbuseReport;
import com.matjari.domain.ExchangeMatch;
import com.matjari.dto.response.ModerationDtos.ReportCreateRequest;
import com.matjari.dto.response.ModerationDtos.ReportCreatedResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.AbuseReportRepository;
import com.matjari.repository.ExchangeMatchRepository;
import com.matjari.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 신고 생성: 입력 검증 -> 참여자 확인 -> 중복·상한 -> INSERT. 상대는 매칭에서 정해지고 아무 행도 잠그지 않는다. */
class AbuseReportServiceTest {

    private ExchangeMatchRepository matchRepository;
    private AbuseReportRepository reportRepository;
    private AbuseReportService service;

    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        reportRepository = mock(AbuseReportRepository.class);
        service = new AbuseReportService(matchRepository, reportRepository, mock(UserRepository.class), mock(PlatformTransactionManager.class));
        ExchangeMatch match = mock(ExchangeMatch.class);
        when(match.getUserAId()).thenReturn(10L);
        when(match.getUserBId()).thenReturn(20L);
        when(matchRepository.findById(50L)).thenReturn(Optional.of(match));
        when(reportRepository.saveAndFlush(any(AbuseReport.class))).thenAnswer(inv -> {
            AbuseReport r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 77L);
            return r;
        });
    }

    @Test
    void 참여자_A가_신고하면_대상은_B이고_상세는_다듬어_저장되며_201_본문을_돌려준다() {
        ReportCreatedResponse response = service.create(10L, 50L, new ReportCreateRequest(" FRAUD ", "  예매내역이 달라요  "));

        ArgumentCaptor<AbuseReport> saved = ArgumentCaptor.forClass(AbuseReport.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReporterId()).isEqualTo(10L);
        assertThat(saved.getValue().getReportedId()).isEqualTo(20L);
        assertThat(saved.getValue().getMatchId()).isEqualTo(50L);
        assertThat(saved.getValue().getReason()).isEqualTo(AbuseReport.Reason.FRAUD);
        assertThat(saved.getValue().getDetail()).isEqualTo("예매내역이 달라요");
        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.status()).isEqualTo("OPEN");
    }

    @Test
    void B가_신고하면_대상은_A이고_상세_없는_ABUSE도_된다() {
        service.create(20L, 50L, new ReportCreateRequest("ABUSE", "   "));
        ArgumentCaptor<AbuseReport> saved = ArgumentCaptor.forClass(AbuseReport.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReportedId()).isEqualTo(10L);
        assertThat(saved.getValue().getDetail()).isNull();
    }

    @Test
    void 비참여자와_없는_매칭은_같은_404이고_아무것도_쓰지_않는다() {
        assertThatThrownBy(() -> service.create(99L, 50L, new ReportCreateRequest("ABUSE", null))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(10L, 404L, new ReportCreateRequest("ABUSE", null))).isInstanceOf(NotFoundException.class);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void 입력_오류는_400_필드_맵이고_매칭을_조회하기_전에_걸러진다() {
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest("NOPE", null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> service.create(10L, 50L, null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest("OTHER", "  ")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("detail"));
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest("ABUSE", "가".repeat(501))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("detail"));
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest(null, "가".repeat(501))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("reason", "detail"));
        verifyNoInteractions(matchRepository, reportRepository);
        service.create(10L, 50L, new ReportCreateRequest("OTHER", "가".repeat(500)));   // 경계: 500자는 허용
        verify(reportRepository).saveAndFlush(any(AbuseReport.class));
    }

    @Test
    void 같은_매칭에_내_OPEN_신고가_있으면_409_REPORT_ALREADY_OPEN() {
        when(reportRepository.existsByReporterIdAndMatchIdAndStatus(10L, 50L, AbuseReport.Status.OPEN)).thenReturn(true);
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest("ABUSE", null)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "REPORT_ALREADY_OPEN"));
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void OPEN_신고가_10건이면_422_REPORT_LIMIT_REACHED_9건이면_접수된다() {
        when(reportRepository.countByReporterIdAndStatus(10L, AbuseReport.Status.OPEN)).thenReturn(10L);
        assertThatThrownBy(() -> service.create(10L, 50L, new ReportCreateRequest("ABUSE", null)))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_LIMIT_REACHED"));
        verify(reportRepository, never()).saveAndFlush(any());

        when(reportRepository.countByReporterIdAndStatus(10L, AbuseReport.Status.OPEN)).thenReturn(9L);
        assertThat(service.create(10L, 50L, new ReportCreateRequest("ABUSE", null)).id()).isEqualTo(77L);
    }
}
