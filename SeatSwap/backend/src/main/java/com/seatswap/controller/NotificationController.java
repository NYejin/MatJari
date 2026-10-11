package com.seatswap.controller;

import com.seatswap.dto.response.NotificationResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.UnreadCountResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 조회·읽음·삭제 API (후속요구사항 알림 3종). 모두 로그인 필요이며 본인 알림만 다룬다(남의 알림·없는 알림은 404).
 * 알림 생성은 제안·예약·예약 취소 트랜잭션 안에서 하므로 여기에 생성 API 는 없다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 내 알림 목록(id 내림차순). size 기본 20 최대 100, 잘못된 page·size·unreadOnly 는 400. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @AuthenticationPrincipal AuthUserPrincipal principal) {
        return notificationService.list(principal.userId(), page, size, unreadOnly);
    }

    /** 안 읽은 개수(전체 + 종류별, 닫힌 매칭 알림 포함). */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return notificationService.unreadCount(principal.userId());
    }

    /** 단건 읽음 -> 204 (이미 읽었어도 멱등). 남의 것·없는 것은 404. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        notificationService.markRead(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 전체 읽음 -> 204. type 을 생략하면 전체, 잘못된 type 은 400. */
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@RequestParam(required = false) String type,
                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        notificationService.markAllRead(principal.userId(), type);
        return ResponseEntity.noContent().build();
    }

    /** 읽은 알림 삭제 -> 200 {"deleted": n}. 안 읽은 알림은 지우지 않는다. */
    @DeleteMapping("/read")
    public NotificationResponse.Deleted deleteRead(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return new NotificationResponse.Deleted(notificationService.deleteRead(principal.userId()));
    }
}
