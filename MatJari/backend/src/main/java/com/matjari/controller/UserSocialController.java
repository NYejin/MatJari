package com.matjari.controller;

import com.matjari.dto.request.SocialConfirmRequest;
import com.matjari.dto.response.SocialAccountsResponse;
import com.matjari.dto.response.SocialConfirmResponse;
import com.matjari.dto.response.SocialLinkResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.social.SocialAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 소셜 계정 연결 관리. 모두 인증 필요(SecurityConfig 의 anyRequest().authenticated()), 응답은 Cache-Control: no-store
 * (연결 시작 응답에 일회용 티켓이 들어 있다). 사용자 식별은 항상 인증 주체의 userId 다.
 */
@RestController
@RequestMapping("/api/users/me/social")
@RequiredArgsConstructor
public class UserSocialController {

    private static final String NO_STORE = "no-store";

    private final SocialAccountService socialAccountService;

    @GetMapping
    public ResponseEntity<SocialAccountsResponse> list(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(socialAccountService.list(principal.userId()));
    }

    @PostMapping("/{provider}/link")
    public ResponseEntity<SocialLinkResponse> link(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                   @PathVariable String provider) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(socialAccountService.startLink(principal.userId(), provider));
    }

    /** 탈퇴 재인증 시작: 이미 연결된 제공자로 다시 인증한다(응답은 연결 시작과 같은 authorizeUrl). 연결이 없으면 409 SOCIAL_NOT_LINKED. */
    @PostMapping("/{provider}/reauth")
    public ResponseEntity<SocialLinkResponse> reauth(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                     @PathVariable String provider) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(socialAccountService.startReauth(principal.userId(), provider));
    }

    /** 연결 확정: 콜백이 프래그먼트로 넘긴 일회용 코드를 로그인한 본인이 제출한다(없음·만료·다른 사용자 400 INVALID_LINK_CODE). */
    @PostMapping("/confirm")
    public ResponseEntity<SocialConfirmResponse> confirm(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                         @Valid @RequestBody SocialConfirmRequest body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(socialAccountService.confirm(principal.userId(), body.code()));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal AuthUserPrincipal principal,
                                       @PathVariable String provider) {
        socialAccountService.unlink(principal.userId(), provider);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}
