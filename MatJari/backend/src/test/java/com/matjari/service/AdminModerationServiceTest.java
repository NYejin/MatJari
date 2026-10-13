package com.matjari.service;

import com.matjari.domain.AbuseReport;
import com.matjari.domain.ChatMessage;
import com.matjari.domain.User;
import com.matjari.domain.UserRole;
import com.matjari.domain.UserSanction;
import com.matjari.dto.response.ModerationDtos.AdminReport;
import com.matjari.dto.response.ModerationDtos.AdminReportMessagePage;
import com.matjari.dto.response.ModerationDtos.ResolveRequest;
import com.matjari.dto.response.ModerationDtos.SanctionInput;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.AbuseReportRepository;
import com.matjari.repository.ChatMessageRepository;
import com.matjari.repository.UserRepository;
import com.matjari.repository.UserSanctionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 처리: 잠금·호출 순서(신고 행 -> 대상 읽기 -> 활성 제재 잠금 읽기 -> canceller -> 제재 INSERT), 거부 규칙, 검증, 채팅 근거 범위. */
class AdminModerationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 12, 0, 0);

    private AbuseReportRepository reportRepository;
    private UserSanctionRepository sanctionRepository;
    private UserRepository userRepository;
    private ChatMessageRepository chatRepository;
    private OpenMatchCanceller canceller;
    private AdminModerationService service;
    private AbuseReport report;
    private User target;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        reportRepository = mock(AbuseReportRepository.class);
        sanctionRepository = mock(UserSanctionRepository.class);
        userRepository = mock(UserRepository.class);
        chatRepository = mock(ChatMessageRepository.class);
        canceller = mock(OpenMatchCanceller.class);
        ObjectProvider<OpenMatchCanceller> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(canceller);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new AdminModerationService(reportRepository, sanctionRepository, userRepository, chatRepository, provider,
                mock(EntityManager.class), clock, mock(com.matjari.websocket.WebSocketSessionRegistry.class));

        report = AbuseReport.create(10L, 20L, 50L, AbuseReport.Reason.ABUSE, "욕설");
        ReflectionTestUtils.setField(report, "id", 9L);
        ReflectionTestUtils.setField(report, "createdAt", NOW.minusDays(1));
        when(reportRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(report));
        when(reportRepository.findById(9L)).thenReturn(Optional.of(report));
        when(reportRepository.findReportedIdById(9L)).thenReturn(Optional.of(20L));
        when(reportRepository.lockAllByReportedId(20L)).thenReturn(List.of(report));

        target = user(20L, "대상", UserRole.USER);
        when(userRepository.findById(20L)).thenReturn(Optional.of(target));
        when(userRepository.findAllById(anyCollection())).thenAnswer(inv -> {
            List<User> users = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                users.add(user((Long) id, "닉" + id, UserRole.USER));
            }
            return users;
        });
        when(sanctionRepository.findActiveForUpdate(anyLong(), any())).thenReturn(List.of());
        when(sanctionRepository.saveAndFlush(any(UserSanction.class))).thenAnswer(inv -> {
            UserSanction s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 31L);
            return s;
        });
        when(reportRepository.countByReported(anyCollection(), any())).thenReturn(List.<Object[]>of(new Object[]{20L, 2L, 1L}));
        when(sanctionRepository.findActiveByUserIds(anyCollection(), any())).thenReturn(List.of());
    }

    private static User user(long id, String nickname, UserRole role) {
        User u = User.create("u" + id + "@t.com", "pw", nickname);
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "role", role);
        return u;
    }

    @Test
    void 제재와_함께_조치하면_순서대로_잠그고_취소한_뒤_제재를_INSERT하고_신고에_sanction_id를_남긴다() {
        AdminReport result = service.resolve(2L, 9L, new ResolveRequest(" 조치함 ", new SanctionInput(7, " 욕설 반복 ")));

        InOrder order = inOrder(reportRepository, userRepository, sanctionRepository, canceller);
        order.verify(reportRepository).lockAllByReportedId(20L);                   // 첫 잠금: 대상의 신고 행 전체(id 순)
        order.verify(userRepository).findById(20L);                                // 대상 읽기(잠금 없음)
        order.verify(sanctionRepository).findActiveForUpdate(20L, NOW);            // 활성 제재 중복(범위 잠금 읽기)
        order.verify(canceller).cancelAllOf(20L);                                  // 티켓 -> 요청 -> 매칭 잠금은 canceller 안
        order.verify(sanctionRepository).saveAndFlush(any(UserSanction.class));    // INSERT 는 canceller 뒤
        verify(userRepository, never()).findByIdForUpdate(anyLong());              // users 행은 잠그지 않는다

        ArgumentCaptor<UserSanction> sanction = ArgumentCaptor.forClass(UserSanction.class);
        verify(sanctionRepository).saveAndFlush(sanction.capture());
        assertThat(sanction.getValue().getUserId()).isEqualTo(20L);
        assertThat(sanction.getValue().getReportId()).isEqualTo(9L);
        assertThat(sanction.getValue().getCreatedById()).isEqualTo(2L);
        assertThat(sanction.getValue().getNote()).isEqualTo("욕설 반복");
        assertThat(sanction.getValue().getStartsAt()).isEqualTo(NOW);
        assertThat(sanction.getValue().getEndsAt()).isEqualTo(NOW.plusDays(7));

        assertThat(report.getStatus()).isEqualTo(AbuseReport.Status.RESOLVED);
        assertThat(report.getSanctionId()).isEqualTo(31L);
        assertThat(report.getHandledById()).isEqualTo(2L);
        assertThat(report.getHandlerNote()).isEqualTo("조치함");
        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.reported().totalReports()).isEqualTo(2);
        assertThat(result.reported().openReports()).isEqualTo(1);
    }

    @Test
    void 무기한_30일도_허용하고_그_외_기간은_400이며_아무것도_잠그지_않는다() {
        service.resolve(2L, 9L, new ResolveRequest(null, new SanctionInput(null, "사유")));
        ArgumentCaptor<UserSanction> sanction = ArgumentCaptor.forClass(UserSanction.class);
        verify(sanctionRepository).saveAndFlush(sanction.capture());
        assertThat(sanction.getValue().getEndsAt()).isNull();

        AbuseReport second = AbuseReport.create(11L, 20L, 51L, AbuseReport.Reason.FRAUD, null);
        ReflectionTestUtils.setField(second, "id", 10L);
        when(reportRepository.findReportedIdById(10L)).thenReturn(Optional.of(20L));
        when(reportRepository.lockAllByReportedId(20L)).thenReturn(List.of(report, second));
        service.resolve(2L, 10L, new ResolveRequest(null, new SanctionInput(30, "사유")));

        assertThatThrownBy(() -> service.resolve(2L, 9L, new ResolveRequest(null, new SanctionInput(14, "사유"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("sanction.days"));
        assertThatThrownBy(() -> service.resolve(2L, 9L, new ResolveRequest(null, new SanctionInput(7, "  "))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("sanction.note"));
        assertThatThrownBy(() -> service.resolve(2L, 9L, new ResolveRequest("가".repeat(301), null)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("note"));
    }

    @Test
    void 제재_없이_조치하면_canceller와_제재_INSERT를_호출하지_않는다() {
        AdminReport result = service.resolve(2L, 9L, null);
        verifyNoInteractions(canceller);
        verify(sanctionRepository, never()).saveAndFlush(any());
        assertThat(report.getStatus()).isEqualTo(AbuseReport.Status.RESOLVED);
        assertThat(report.getSanctionId()).isNull();
        assertThat(result.status()).isEqualTo("RESOLVED");
    }

    @Test
    void ADMIN_대상은_409_CANNOT_SANCTION_ADMIN이고_취소와_INSERT_없이_신고도_그대로다() {
        ReflectionTestUtils.setField(target, "role", UserRole.ADMIN);

        assertThatThrownBy(() -> service.resolve(2L, 9L, new ResolveRequest(null, new SanctionInput(7, "사유"))))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "CANNOT_SANCTION_ADMIN"));
        verifyNoInteractions(canceller);
        verify(sanctionRepository, never()).saveAndFlush(any());
        assertThat(report.isOpen()).isTrue();
    }

    @Test
    void 이미_정지_중인_대상은_409_SANCTION_ALREADY_ACTIVE() {
        when(sanctionRepository.findActiveForUpdate(20L, NOW)).thenReturn(List.of(UserSanction.suspend(20L, null, "x", NOW.minusDays(1), 7, 2L)));
        assertThatThrownBy(() -> service.resolve(2L, 9L, new ResolveRequest(null, new SanctionInput(7, "사유"))))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "SANCTION_ALREADY_ACTIVE"));
        verifyNoInteractions(canceller);
    }

    @Test
    void 처리된_신고는_resolve_dismiss_모두_409_REPORT_ALREADY_HANDLED() {
        service.dismiss(2L, 9L, "기각");
        assertThat(report.getStatus()).isEqualTo(AbuseReport.Status.DISMISSED);

        assertThatThrownBy(() -> service.resolve(2L, 9L, null)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "REPORT_ALREADY_HANDLED"));
        assertThatThrownBy(() -> service.dismiss(2L, 9L, null)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(canceller);
    }

    @Test
    void 없는_신고는_404() {
        when(reportRepository.findReportedIdById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(2L, 404L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getReport(404L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.messages(404L, null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 해제는_활성_제재만_되고_이미_해제됐거나_만료됐으면_409_SANCTION_NOT_ACTIVE() {
        UserSanction active = UserSanction.suspend(20L, 9L, "사유", NOW.minusDays(1), 7, 2L);
        ReflectionTestUtils.setField(active, "id", 31L);
        when(sanctionRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(active));

        var response = service.revoke(3L, 31L);
        assertThat(response.active()).isFalse();
        assertThat(active.getRevokedAt()).isEqualTo(NOW);
        assertThat(active.getRevokedById()).isEqualTo(3L);
        assertThatThrownBy(() -> service.revoke(3L, 31L)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "SANCTION_NOT_ACTIVE"));

        UserSanction expired = UserSanction.suspend(20L, null, "사유", NOW.minusDays(30), 7, 2L);
        ReflectionTestUtils.setField(expired, "id", 32L);
        when(sanctionRepository.findByIdForUpdate(32L)).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.revoke(3L, 32L)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.revoke(3L, 404L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 채팅_근거는_신고된_매칭의_메시지만_조회하고_size_초과분으로_hasMore를_판단한다() {
        ChatMessage m1 = ChatMessage.of(50L, 10L, "첫째", "c1");
        ReflectionTestUtils.setField(m1, "id", 5L);
        ChatMessage m2 = ChatMessage.of(50L, 20L, "둘째", "c2");
        ReflectionTestUtils.setField(m2, "id", 4L);
        ChatMessage m3 = ChatMessage.of(50L, 10L, "셋째", "c3");
        ReflectionTestUtils.setField(m3, "id", 3L);
        when(chatRepository.findLatest(50L, 3)).thenReturn(List.of(m1, m2, m3));
        when(chatRepository.findBefore(50L, 3L, 201)).thenReturn(List.of());

        AdminReportMessagePage page = service.messages(9L, null, 2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.messages()).extracting("id").containsExactly(5L, 4L);
        assertThat(page.messages().get(0).senderNickname()).isEqualTo("닉10");
        assertThat(page.messages().get(1).senderNickname()).isEqualTo("닉20");

        assertThat(service.messages(9L, 3L, 999).hasMore()).isFalse();     // size 상한 200 -> 조회 201건
        verify(chatRepository, never()).findLatest(eq(51L), any(Integer.class));
        assertThatThrownBy(() -> service.messages(9L, null, 0)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 목록_필터_값이_잘못되면_400() {
        assertThatThrownBy(() -> service.listReports("X", null, 0, 20)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("status"));
        assertThatThrownBy(() -> service.listReports(null, "X", 0, 20)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> service.listSanctions("maybe", 0, 20)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("active"));
        assertThatThrownBy(() -> service.listBlocks(-1, 20)).isInstanceOf(FieldValidationException.class);
    }
}
