package com.matjari.controller;

import com.matjari.dto.response.ListingImageRef;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.ListingImageAccessService;
import com.matjari.service.ListingImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 티켓('내 자리')·교환 희망 조건('원하는 자리') 사진 API (V33). 모두 로그인 필요.
 * 올리기·지우기는 본인 것만(남의 것은 404), 열람은 소유자·관리자·매칭 상대·지금 후보 관계인 사용자만이고 그 밖은 404다.
 */
@RestController
@RequiredArgsConstructor
public class ListingImageController {

    private final ListingImageService imageService;
    private final ListingImageAccessService accessService;

    /**
     * 티켓 사진 올리기(multipart image) -> 201 {id}. 남의 티켓·없는 티켓 404, 내린 티켓 409 TICKET_NOT_ACTIVE, 3장 초과 422 IMAGE_LIMIT_REACHED,
     * 형식·크기·화소 400 image, 5MiB 초과 413 IMAGE_TOO_LARGE, 속도 제한 429 LISTING_IMAGE_RATE_LIMITED, 동시 처리 한도 503 SERVER_BUSY.
     */
    @PostMapping(path = "/api/tickets/{ticketId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ListingImageRef> uploadTicketImage(@PathVariable Long ticketId,
                                                             @RequestParam(required = false) MultipartFile image,
                                                             @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.uploadForTicket(principal.userId(), ticketId, image));
    }

    /** 티켓 사진 지우기 -> 204(멱등). 남의 티켓은 404. */
    @DeleteMapping("/api/tickets/{ticketId}/images/{imageId}")
    public ResponseEntity<Void> deleteTicketImage(@PathVariable Long ticketId, @PathVariable Long imageId,
                                                  @AuthenticationPrincipal AuthUserPrincipal principal) {
        imageService.deleteForTicket(principal.userId(), ticketId, imageId);
        return ResponseEntity.noContent().build();
    }

    /** 교환 희망 조건 사진 올리기 -> 201 {id}. 오류는 티켓 사진과 같고, 삭제된 요청은 409 REQUEST_DELETED. */
    @PostMapping(path = "/api/exchange/requests/{requestId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ListingImageRef> uploadRequestImage(@PathVariable Long requestId,
                                                              @RequestParam(required = false) MultipartFile image,
                                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(imageService.uploadForRequest(principal.userId(), requestId, image));
    }

    /** 교환 희망 조건 사진 지우기 -> 204(멱등). 남의 요청은 404. */
    @DeleteMapping("/api/exchange/requests/{requestId}/images/{imageId}")
    public ResponseEntity<Void> deleteRequestImage(@PathVariable Long requestId, @PathVariable Long imageId,
                                                   @AuthenticationPrincipal AuthUserPrincipal principal) {
        imageService.deleteForRequest(principal.userId(), requestId, imageId);
        return ResponseEntity.noContent().build();
    }

    /** 사진 바이트. 권한이 없으면 404, 정리된 사진은 410 IMAGE_EXPIRED. 캐시·형식 추측을 막는 헤더를 고정한다. */
    @GetMapping("/api/listing-images/{imageId}")
    public ResponseEntity<byte[]> view(@PathVariable Long imageId, @AuthenticationPrincipal AuthUserPrincipal principal) {
        boolean admin = principal.authorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ChatController.imageResponse(accessService.view(principal.userId(), admin, imageId));
    }
}
