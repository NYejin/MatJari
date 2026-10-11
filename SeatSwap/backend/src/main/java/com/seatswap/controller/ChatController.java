package com.seatswap.controller;

import com.seatswap.dto.response.ChatDtos;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매칭 채팅 REST (보내기·목록·읽음). 모두 로그인 필요, 매칭 참여자만(비참여자·없는 매칭은 404). 받기는 STOMP /user/queue/chat 이다.
 * 종료(CANCELED·COMPLETED)된 매칭은 읽기만 되고 전송은 409 MATCH_STATE_CONFLICT 이다. 규칙·오류는 {@link ChatService} Javadoc 참고.
 */
@RestController
@RequestMapping("/api/exchange/matches/{id}/messages")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 전송 -> 201(새 메시지) / 200(같은 clientMessageId 의 재전송, 기존 메시지). 400 본문·ID, 404 비참여자, 409 종료된 매칭, 429 속도 제한. */
    @PostMapping
    public ResponseEntity<ChatDtos.Message> send(@PathVariable Long id,
                                                 @Valid @RequestBody ChatDtos.SendRequest request,
                                                 @AuthenticationPrincipal AuthUserPrincipal principal) {
        ChatService.SendResult result = chatService.send(principal.userId(), id, request.content(), request.clientMessageId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
    }

    /**
     * 목록. 기본은 최신 size(30)건, before=메시지 id 보다 오래된 건(위로 스크롤), after=그보다 새 건(재연결 뒤 빈 구간, size 기본 100).
     * 응답 messages 는 id 오름차순이고 myLastReadMessageId / counterpartLastReadMessageId 를 함께 준다. 종료된 매칭도 열람 가능.
     */
    @GetMapping
    public ChatDtos.MessagePage list(@PathVariable Long id,
                                     @RequestParam(required = false) Long before,
                                     @RequestParam(required = false) Long after,
                                     @RequestParam(required = false) Integer size,
                                     @AuthenticationPrincipal AuthUserPrincipal principal) {
        return chatService.list(principal.userId(), id, before, after, size);
    }

    /** 읽음 포인터 갱신 -> 204 (줄어들지 않음, 그 매칭의 실제 메시지 id 로 잘라 저장). 성공해 값이 올라가면 상대에게 READ 푸시. */
    @PutMapping("/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id,
                                         @Valid @RequestBody ChatDtos.ReadRequest request,
                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        chatService.markRead(principal.userId(), id, request.lastReadMessageId());
        return ResponseEntity.noContent().build();
    }
}
