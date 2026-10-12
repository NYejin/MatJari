package com.seatswap.controller;

import com.seatswap.dto.request.LoginRequest;
import com.seatswap.dto.request.RefreshRequest;
import com.seatswap.dto.request.SignupRequest;
import com.seatswap.dto.response.TokenResponse;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.ok(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /**
     * 로그아웃. JWT 는 무상태라 서버가 지울 것이 없고 클라이언트가 토큰을 버린다. 이용 정지 중인 사용자도 호출할 수 있도록(JwtAuthenticationFilter 가
     * 이 경로만 정지 검사를 건너뛴다) 항상 204 로 응답하는 자리만 둔다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
