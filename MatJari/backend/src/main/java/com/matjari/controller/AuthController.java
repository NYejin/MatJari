package com.matjari.controller;

import com.matjari.dto.request.EmailCodeRequest;
import com.matjari.dto.request.LoginRequest;
import com.matjari.dto.request.SignupRequest;
import com.matjari.dto.response.EmailCodeResponse;
import com.matjari.dto.response.EmailVerificationStatusResponse;
import com.matjari.dto.response.TokenResponse;
import com.matjari.dto.response.UserResponse;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.exception.MatJariException;
import com.matjari.security.AuthRateLimiter;
import com.matjari.security.ClientIpResolver;
import com.matjari.security.RefreshTokenCookie;
import com.matjari.service.AuthService;
import com.matjari.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final AuthRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final RefreshTokenCookie refreshCookie;

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        // IP당 1시간 10회(시도마다 센다). 초과 429 SIGNUP_RATE_LIMITED
        rateLimiter.acquireSignup(clientIpResolver.resolve(http));
        return ResponseEntity.ok(authService.signup(request));
    }

    /** 가입 화면이 인증번호 칸을 보일지 정하는 공개 조회(로그인 불필요). */
    @GetMapping("/signup/email-verification")
    public ResponseEntity<EmailVerificationStatusResponse> emailVerificationStatus() {
        return ResponseEntity.ok(new EmailVerificationStatusResponse(emailVerificationService.isEnabled()));
    }

    /** 가입 전 이메일 인증번호 발송. 이미 가입된 이메일은 400 email, 너무 잦으면 429. */
    @PostMapping("/signup/email-code")
    public ResponseEntity<EmailCodeResponse> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                                           HttpServletRequest http) {
        return ResponseEntity.ok(emailVerificationService.sendCode(request.email(), clientIpResolver.resolve(http)));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // 계정별·IP별 실패 제한(없는 이메일도 똑같이 센다). 잠겨 있으면 429 LOGIN_RATE_LIMITED
        String ip = clientIpResolver.resolve(http);
        rateLimiter.checkLogin(request.email(), ip);
        try {
            AuthService.IssuedTokens tokens = authService.login(request);
            rateLimiter.recordLoginSuccess(request.email());
            return tokenResponse(tokens);
        } catch (AccountSuspendedException e) {
            // 비밀번호는 맞았다(403 + withdrawToken 흐름은 그대로) — 실패로 세지 않는다
            rateLimiter.recordLoginSuccess(request.email());
            throw e;
        } catch (MatJariException e) {
            rateLimiter.recordLoginFailure(request.email(), ip);
            throw e;
        }
    }

    /** 리프레시 쿠키(mj_rt)로 재발급·회전. X-Requested-With 헤더 필수(없으면 403). 새 리프레시 쿠키를 다시 내려 준다. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest http) {
        refreshCookie.requireAjaxHeader(http);
        return tokenResponse(authService.refresh(refreshCookie.read(http)));
    }

    /**
     * 로그아웃. 쿠키 토큰의 계열을 폐기하고 쿠키를 지운다. 토큰이 없거나 모르는 값이어도 204(멱등). X-Requested-With 헤더 필수(없으면 403).
     * 액세스 토큰은 무상태라 클라이언트가 버린다. 이용 정지 중인 사용자도 호출할 수 있다(JwtAuthenticationFilter 가 이 경로의 정지 검사를 건너뛴다).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        refreshCookie.requireAjaxHeader(http);
        authService.logout(refreshCookie.read(http));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear()).build();
    }

    private ResponseEntity<TokenResponse> tokenResponse(AuthService.IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.create(tokens.refreshToken()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(tokens.body());
    }
}
