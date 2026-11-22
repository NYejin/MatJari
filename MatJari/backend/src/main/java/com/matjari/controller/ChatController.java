package com.matjari.controller;

import com.matjari.dto.response.ChatDtos;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.matjari.service.ChatImageStore;
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
     * 사진 전송(V30, multipart: image 파일 + clientMessageId) -> 201 / 200(재전송). 텍스트 전송과 같은 규칙 + 400 image(형식·크기·화소),
     * 413 IMAGE_TOO_LARGE(5MiB 초과), 429 CHAT_IMAGE_RATE_LIMITED(하루 30장).
     */
    @PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatDtos.Message> sendImage(@PathVariable Long id,
                                                      @RequestParam(required = false) MultipartFile image,
                                                      @RequestParam(required = false) String clientMessageId,
                                                      @AuthenticationPrincipal AuthUserPrincipal principal) {
        ChatService.SendResult result = chatService.sendImage(principal.userId(), id, image, clientMessageId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
    }

    /** 사진 바이트. 참여자만(404), 지워졌으면 410 IMAGE_EXPIRED. 캐시·형식 추측·내려받기를 막는 헤더를 고정한다. */
    @GetMapping("/{messageId}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable Long messageId,
                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        return imageResponse(chatService.image(principal.userId(), id, messageId));
    }

    /** 참여자·관리자 경로가 함께 쓰는 사진 응답 헤더. */
    public static ResponseEntity<byte[]> imageResponse(ChatImageStore.ImageData data) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(data.bytes());
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
