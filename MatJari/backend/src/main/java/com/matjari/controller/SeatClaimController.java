package com.matjari.controller;

import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.SeatClaimDtos.SeatClaimResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.SeatClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 좌석 소유 인증 요청 API ('내 티켓 인증'). 모두 로그인 필요. 요청 필드는 서비스가 검증해 {필드: 메시지} 400 으로 모아 돌려주므로
 * 파라미터는 required=false 로 받는다. 이미지는 요청자 본인 또는 ADMIN 만 볼 수 있다(그 외 404).
 */
@RestController
@RequestMapping("/api/seat-claims")
@RequiredArgsConstructor
public class SeatClaimController {

    private final SeatClaimService seatClaimService;

    /** 201 + 요청. 400 필드 오류, 422 SESSION_CLOSED/SEAT_NOT_REGISTERED/SEAT_CLAIM_LIMIT_REACHED, 409, 413 IMAGE_TOO_LARGE. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SeatClaimResponse> create(@RequestParam(required = false) Long sessionId,
                                                    @RequestParam(required = false) String zone,
                                                    @RequestParam(required = false) String row,
                                                    @RequestParam(required = false) String col,
                                                    @RequestParam(required = false) String memo,
                                                    @RequestParam(required = false) MultipartFile image,
                                                    @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(seatClaimService.create(principal.userId(), sessionId, zone, row, col, memo, image));
    }

    @GetMapping("/me")
    public PageResponse<SeatClaimResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @AuthenticationPrincipal AuthUserPrincipal principal) {
        return seatClaimService.listMine(principal.userId(), page, size);
    }

    /** 사진 바이트. 본인·ADMIN 만(그 외 404), 삭제됐으면 410 IMAGE_DELETED. 브라우저가 해석을 바꾸거나 저장하지 않도록 헤더를 고정한다. */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        SeatClaimService.ImageData data = seatClaimService.image(principal.userId(), principal.isAdmin(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(data.bytes());
    }
}
