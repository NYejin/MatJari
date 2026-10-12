package com.seatswap.controller;

import com.seatswap.dto.request.UpdateNicknameRequest;
import com.seatswap.dto.response.UserResponse;
import jakarta.validation.Valid;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.UserService;
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

    /**
     * 내 정보 조회. 인증 주체는 JwtAuthenticationFilter가 채운 AuthUserPrincipal이며
     * 사용자 식별은 userId 기준 (email 아님).
     * 미인증 요청은 SecurityConfig의 anyRequest().authenticated()에서 막혀 여기까지 오지 않는다.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.ok(userService.getMe(principal.userId()));
    }

    /** 닉네임 수정(FR-01). 같은 값이면 멱등 200. */
    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                 @Valid @RequestBody UpdateNicknameRequest body) {
        return ResponseEntity.ok(userService.updateNickname(principal.userId(), body.nickname()));
    }
}
