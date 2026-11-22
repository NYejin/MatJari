package com.matjari.service;

import com.matjari.domain.NotificationType;
import com.matjari.repository.NotificationMailRepository;
import com.matjari.repository.NotificationMailRepository.Pending;
import com.matjari.repository.NotificationMailRepository.Recipient;
import com.matjari.service.mail.EmailSender;
import com.matjari.service.social.SocialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 메일 알림 스케줄러 로직: 사용자당 요약 1통, 쿨다운, 건너뛰기, 시간당 상한, 실패 처리, 본문에 개인정보 없음. 저장소는 mock. */
class NotificationMailServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 12, 0);
    private static final LocalDateTime CUTOFF = NOW.minusMinutes(5);
    private static final LocalDateTime FLOOR = NOW.minusHours(24);

    private NotificationMailRepository repository;
    private EmailSender sender;
    private SocialProperties social;
    private NotificationMailService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationMailRepository.class);
        sender = mock(EmailSender.class);
        social = new SocialProperties();
        social.setFrontendBaseUrl("https://matjari.example/");
        service = newService(100);
        when(repository.stillUnreadIds(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.performanceTitles(anyList())).thenReturn(Map.of());
    }

    private NotificationMailService newService(int maxPerHour) {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        return new NotificationMailService(repository, sender, social, clock, new com.matjari.service.mail.MailDeliveryPolicy("smtp", "https://x.test"), true, 5, 24, 30, maxPerHour);
    }

    private static Pending p(long id, long userId, NotificationType type) {
        return new Pending(id, userId, type);
    }

    private static Recipient ok(String email) {
        return new Recipient(email, false, true, true, null);
    }

    private void pending(Pending... items) {
        when(repository.findPending(CUTOFF, FLOOR, NotificationMailService.SCAN_LIMIT)).thenReturn(List.of(items));
    }

    @Test
    void 사용자당_메일_한_통으로_종류별_요약하고_알림_id만_표시한다() {
        pending(p(1, 10, NotificationType.PROPOSAL_RECEIVED), p(2, 10, NotificationType.CHAT_MESSAGE),
                p(3, 10, NotificationType.CHAT_MESSAGE), p(4, 10, NotificationType.NEW_CANDIDATE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.performanceTitles(anyList())).thenReturn(Map.of(1L, "햄릿", 4L, "햄릿"));

        assertThat(service.runOnce()).isEqualTo(1);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("a@b.com"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("[맞자리] 새 알림 4건");
        assertThat(body.getValue())
                .contains("새 교환 제안 1건 — 햄릿", "새 교환 후보 1건 — 햄릿", "새 채팅 메시지 2건")
                .contains("https://matjari.example/notifications")
                .contains("마이페이지 > 알림 설정에서 끌 수 있어요: https://matjari.example/me");
        verify(repository).markEmailed(List.of(1L, 2L, 3L, 4L), NOW, CUTOFF);
        verify(repository).touchLastEmail(10L, NOW);
    }

    @Test
    void 설정이_꺼졌거나_자리표시_이메일이면_보내지_않고_다시_스캔하지_않게_표시한다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 11, NotificationType.CHAT_MESSAGE),
                p(3, 12, NotificationType.CHAT_MESSAGE), p(4, 13, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(new Recipient("a@b.com", false, false, true, null));
        when(repository.loadRecipient(11L)).thenReturn(ok("google.abc@social.matjari.invalid"));
        when(repository.loadRecipient(12L)).thenReturn(new Recipient("withdrawn.12@withdrawn.matjari.invalid", true, true, true, null));
        when(repository.loadRecipient(13L)).thenReturn(null);

        assertThat(service.runOnce()).isZero();

        verify(sender, never()).send(anyString(), anyString(), anyString());
        verify(repository).markEmailed(List.of(1L), NOW, CUTOFF);
        verify(repository).markEmailed(List.of(2L), NOW, CUTOFF);
        verify(repository).markEmailed(List.of(3L), NOW, CUTOFF);
        verify(repository).markEmailed(List.of(4L), NOW, CUTOFF);
    }

    @Test
    void 이용_정지_중이면_건너뛰되_표시하지_않는다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.isSuspended(10L, NOW)).thenReturn(true);

        assertThat(service.runOnce()).isZero();

        verify(sender, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).markEmailed(anyList(), any(), any());
    }

    @Test
    void 쿨다운_안이면_알림을_남겨_두고_30분이_지나면_보낸다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(new Recipient("a@b.com", false, true, true, NOW.minusMinutes(29)));
        assertThat(service.runOnce()).isZero();
        verify(sender, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).markEmailed(anyList(), any(), any());

        when(repository.loadRecipient(10L)).thenReturn(new Recipient("a@b.com", false, true, true, NOW.minusMinutes(31)));
        assertThat(service.runOnce()).isEqualTo(1);
        verify(sender).send(eq("a@b.com"), anyString(), anyString());
    }

    @Test
    void 지연_동안_읽은_알림은_빼고_모두_읽었으면_메일을_보내지_않는다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 10, NotificationType.RESERVATION_PROPOSED));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.stillUnreadIds(List.of(1L, 2L), CUTOFF)).thenReturn(List.of(2L));

        assertThat(service.runOnce()).isEqualTo(1);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("a@b.com"), eq("[맞자리] 새 알림 1건"), body.capture());
        assertThat(body.getValue()).contains("예약 제안 1건").doesNotContain("채팅");
        verify(repository).markEmailed(List.of(2L), NOW, CUTOFF);

        when(repository.stillUnreadIds(List.of(1L, 2L), CUTOFF)).thenReturn(List.of());
        service = newService(100);
        org.mockito.Mockito.clearInvocations(sender);
        assertThat(service.runOnce()).isZero();
        verify(sender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 시간당_상한에_닿으면_남은_사용자는_다음_주기로_미룬다() {
        service = newService(2);
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 11, NotificationType.CHAT_MESSAGE), p(3, 12, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.loadRecipient(11L)).thenReturn(ok("b@b.com"));
        when(repository.loadRecipient(12L)).thenReturn(ok("c@b.com"));

        assertThat(service.runOnce()).isEqualTo(2);

        verify(sender).send(eq("a@b.com"), anyString(), anyString());
        verify(sender).send(eq("b@b.com"), anyString(), anyString());
        verify(sender, never()).send(eq("c@b.com"), anyString(), anyString());
        verify(repository, never()).markEmailed(eq(List.of(3L)), any(), any());
    }

    @Test
    void 발송이_실패하면_그대로_두고_다른_사용자는_계속한다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 11, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.loadRecipient(11L)).thenReturn(ok("b@b.com"));
        doThrow(new EmailSender.EmailSendException("smtp down", null)).when(sender).send(eq("a@b.com"), anyString(), anyString());

        assertThat(service.runOnce()).isEqualTo(1);

        verify(repository, never()).markEmailed(eq(List.of(1L)), any(), any());
        verify(repository, never()).touchLastEmail(eq(10L), any());
        verify(repository).markEmailed(List.of(2L), NOW, CUTOFF);
        verify(repository).touchLastEmail(11L, NOW);
    }

    @Test
    void 꺼져_있으면_아무것도_읽지_않고_저장소_예외도_삼킨다() {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        NotificationMailService off = new NotificationMailService(repository, sender, social, clock, new com.matjari.service.mail.MailDeliveryPolicy("smtp", "https://x.test"), false, 5, 24, 30, 100);
        assertThat(off.runOnce()).isZero();
        verify(repository, never()).findPending(any(), any(), org.mockito.ArgumentMatchers.anyInt());

        when(repository.findPending(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("db down"));
        assertThat(service.runOnce()).isZero();
    }

    @Test
    void 본문에는_닉네임_좌석_채팅_내용이_없고_제목의_줄바꿈은_공백이_된다() {
        String body = NotificationMailService.buildBody(
                List.of(p(1, 10, NotificationType.PROPOSAL_RECEIVED), p(2, 10, NotificationType.RESERVATION_REJECTED),
                        p(3, 10, NotificationType.MATCH_RESERVED), p(4, 10, NotificationType.RESERVATION_CANCELED)),
                Map.of(1L, NotificationMailService.cleanTitle("뮤지컬\n  레미제라블")), "https://x.test");
        assertThat(body).contains("새 교환 제안 1건 — 뮤지컬 레미제라블", "예약 제안 거절 1건",
                "상대가 예약을 수락한 교환 1건", "상대가 예약을 취소한 교환 1건");
        assertThat(NotificationMailService.cleanTitle("a".repeat(150))).hasSize(61).endsWith("…");
    }

    @Test
    void 이메일_소유가_확인되지_않은_사용자는_보내지_않고_다시_스캔하지_않게_표시한다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(new Recipient("a@b.com", false, true, false, null));

        assertThat(service.runOnce()).isZero();

        verify(sender, never()).send(anyString(), anyString(), anyString());
        verify(repository).markEmailed(List.of(1L), NOW, CUTOFF);
        verify(repository, never()).touchLastEmail(eq(10L), any());
    }

    @Test
    void 발송이_연속_2번_실패하면_그_주기를_끝낸다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 11, NotificationType.CHAT_MESSAGE), p(3, 12, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.loadRecipient(11L)).thenReturn(ok("b@b.com"));
        when(repository.loadRecipient(12L)).thenReturn(ok("c@b.com"));
        org.mockito.Mockito.doThrow(new com.matjari.service.mail.EmailSender.EmailSendException("smtp down", null))
                .when(sender).send(anyString(), anyString(), anyString());

        assertThat(service.runOnce()).isZero();

        verify(sender, org.mockito.Mockito.times(2)).send(anyString(), anyString(), anyString());
        verify(repository, never()).markEmailed(anyList(), any(), any());
    }

    @Test
    void 성공이_끼면_연속_실패로_세지_않는다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE), p(2, 11, NotificationType.CHAT_MESSAGE), p(3, 12, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        when(repository.loadRecipient(11L)).thenReturn(ok("b@b.com"));
        when(repository.loadRecipient(12L)).thenReturn(ok("c@b.com"));
        org.mockito.Mockito.doThrow(new com.matjari.service.mail.EmailSender.EmailSendException("fail", null))
                .doNothing().doThrow(new com.matjari.service.mail.EmailSender.EmailSendException("fail", null))
                .when(sender).send(anyString(), anyString(), anyString());

        assertThat(service.runOnce()).isEqualTo(1);

        verify(sender, org.mockito.Mockito.times(3)).send(anyString(), anyString(), anyString());
    }

    @Test
    void 발송_뒤_last_email_at을_emailed_at보다_먼저_기록한다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));

        service.runOnce();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sender, repository);
        order.verify(sender).send(eq("a@b.com"), anyString(), anyString());
        order.verify(repository).touchLastEmail(10L, NOW);
        order.verify(repository).markEmailed(List.of(1L), NOW, CUTOFF);
    }

    @Test
    void DB_기록이_실패해도_메모리_쿨다운으로_다음_주기에_다시_보내지_않는다() {
        pending(p(1, 10, NotificationType.CHAT_MESSAGE));
        when(repository.loadRecipient(10L)).thenReturn(ok("a@b.com"));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(repository).touchLastEmail(eq(10L), any());

        assertThat(service.runOnce()).isZero();
        assertThat(service.runOnce()).isZero();

        verify(sender, org.mockito.Mockito.times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void log_모드이면서_백엔드가_루프백이_아니면_아무것도_읽지_않는다() {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        NotificationMailService off = new NotificationMailService(repository, sender, social, clock,
                new com.matjari.service.mail.MailDeliveryPolicy("log", "https://matjari.example.com"), true, 5, 24, 30, 100);
        assertThat(off.runOnce()).isZero();
        verify(repository, never()).findPending(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        // 루프백(개발)이면 log 모드여도 돈다
        NotificationMailService dev = new NotificationMailService(repository, sender, social, clock,
                new com.matjari.service.mail.MailDeliveryPolicy("log", "http://localhost:8080"), true, 5, 24, 30, 100);
        dev.runOnce();
        verify(repository).findPending(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 제목의_URL_모양_문자열은_링크_생략으로_바뀌고_60자로_잘린다() {
        assertThat(NotificationMailService.cleanTitle("햄릿 http://evil.example/login 보기")).isEqualTo("햄릿 [링크 생략] 보기");
        assertThat(NotificationMailService.cleanTitle("지금 확인 www.evil.com/a?b=1")).isEqualTo("지금 확인 [링크 생략]");
        assertThat(NotificationMailService.cleanTitle("evil.com 무료 티켓")).isEqualTo("[링크 생략] 무료 티켓");
        assertThat(NotificationMailService.cleanTitle("HTTPS://EVIL.KR")).isEqualTo("[링크 생략]");
        assertThat(NotificationMailService.cleanTitle("뮤지컬 레미제라블 Vol.2")).isEqualTo("뮤지컬 레미제라블 Vol.2");
        assertThat(NotificationMailService.cleanTitle("가".repeat(80))).isEqualTo("가".repeat(60) + "…");
    }
}
