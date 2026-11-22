package com.matjari.controller;

import com.matjari.dto.request.PasswordRequests;
import com.matjari.dto.request.UpdateNicknameRequest;
import com.matjari.security.ClientIpResolver;
import com.matjari.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import com.matjari.dto.request.WithdrawRequest;
import com.matjari.service.WithdrawService;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.matjari.dto.response.UserResponse;
import jakarta.validation.Valid;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final WithdrawService withdrawService;
    private final PasswordResetService passwordResetService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 내 정보 조회. 인증 주체는 JwtAuthenticationFilter가 채운 AuthUserPrincipal이며
     * 사용자 식별은 userId 기준 (email 아님).
     * 미인증 요청은 SecurityConfig의 anyRequest().authenticated()에서 막혀 여기까지 오지 않는다.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.ok(userService.getMe(principal.userId()));
    }

    /**
     * 회원 탈퇴(FR-01). 본문 {password?, confirmText?}: 비밀번호 계정은 password, 소셜 전용 계정은 confirmText="탈퇴합니다".
     * 성공 204(토큰은 즉시 무효). 400 비밀번호/확인 문구 불일치, 409 ADMIN_CANNOT_WITHDRAW, 429 WITHDRAW_RATE_LIMITED.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthUserPrincipal principal,
                                         @RequestBody(required = false) WithdrawRequest body) {
        withdrawService.withdraw(principal.userId(), body);
        return ResponseEntity.noContent().build();
    }

    /**
     * 비밀번호 변경(로그인 상태). 비밀번호 로그인 계정만(소셜 전용은 409 PASSWORD_LOGIN_DISABLED), 현재 비밀번호가 틀리면 400 currentPassword
     * (로그인 실패 제한에 센다, 잠기면 429 LOGIN_RATE_LIMITED), 새 비밀번호가 같으면 400 newPassword. 성공 200 + 새 액세스 토큰(TokenResponse, 이전 액세스 토큰은 거부된다).
     * 리프레시 쿠키(Path=/api/auth)는 이 경로에 오지 않아 어느 기기가 현재인지 알 수 없으므로 모든 로그인 유지 토큰을 폐기한다.
     * 현재 기기를 유지하려면 쿠키가 실리는 {@code PATCH /api/auth/password} 를 쓴다.
     */
    @PatchMapping("/me/password")
    public ResponseEntity<com.matjari.dto.response.TokenResponse> changePassword(@AuthenticationPrincipal AuthUserPrincipal principal,
                                               @Valid @RequestBody PasswordRequests.ChangeRequest body,
                                               HttpServletRequest http) {
        com.matjari.dto.response.TokenResponse token = passwordResetService.changePassword(principal.userId(), principal.email(),
                clientIpResolver.resolve(http), body.currentPassword(), body.newPassword(), null);
        return ResponseEntity.ok().header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store").body(token);
    }

    /** 닉네임 수정(FR-01). 같은 값이면 멱등 200. */
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                 @Valid @RequestBody UpdateNicknameRequest body) {
        return ResponseEntity.ok(userService.updateNickname(principal.userId(), body.nickname()));
    }
}
