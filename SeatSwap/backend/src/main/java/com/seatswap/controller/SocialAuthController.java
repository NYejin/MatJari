package com.seatswap.controller;

import com.seatswap.dto.request.SocialExchangeRequest;
import com.seatswap.dto.response.TokenResponse;
import com.seatswap.service.social.SocialAuthService;
import com.seatswap.service.social.SocialStateCookie;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 소셜 로그인(카카오·구글·네이버) 엔드포인트. /api/auth/** 는 SecurityConfig 에서 이미 permitAll.
 * 모든 응답에 Cache-Control: no-store. 리다이렉트 목적지는 서버 설정으로만 정해진다.
 */
@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public class SocialAuthController {

    private static final String NO_STORE = "no-store";

    private final SocialAuthService socialAuthService;

    /** 활성 제공자 목록(프런트가 버튼 표시에 사용). 인증 불필요. */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, List<String>>> providers() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(Map.of("providers", socialAuthService.enabledProviders()));
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable String provider, HttpServletRequest request) {
        // X-Forwarded-For 는 위조할 수 있어 신뢰하지 않는다
        SocialAuthService.AuthorizeResult result = socialAuthService.authorize(provider, request.getRemoteAddr());
        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, result.location())
                .header(HttpHeaders.SET_COOKIE, result.setCookie())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        SocialAuthService.CallbackResult result =
                socialAuthService.callbackRateLimited(request.getRemoteAddr())
                        ? socialAuthService.busyResult()
                        : socialAuthService.callback(provider, code, state, error, cookieValue(request));
        return ResponseEntity.status(302)
                .location(URI.create(result.location()))
                .header(HttpHeaders.SET_COOKIE, result.clearCookie())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    @PostMapping("/exchange")
    public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody SocialExchangeRequest body, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(socialAuthService.exchange(body.code(), request.getRemoteAddr()));
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (SocialStateCookie.NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
