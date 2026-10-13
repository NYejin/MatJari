package com.matjari.security;

import com.matjari.config.SecurityConfig;
import com.matjari.controller.AuthController;
import com.matjari.domain.User;
import com.matjari.repository.UserRepository;
import com.matjari.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CVE-2025-22228 대응(Spring Security 6.3.8+) 이후 HTTP 레벨 회귀 테스트.
 * 실제 AuthService + SecurityConfig의 BCryptPasswordEncoder + GlobalExceptionHandler를 사용하고
 * 저장소/JWT만 mock 한다. 72바이트 초과 비밀번호가 BCrypt의 IllegalArgumentException(→500)까지
 * 가지 않고 400으로 끝나는지 확인한다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, AuthService.class})
class AuthLongPasswordSliceTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private UserRepository userRepository;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AccountSuspensionChecker suspensionChecker;
    @MockBean
    private com.matjari.service.EmailVerificationService emailVerificationService;

    @Test
    void loginWithPasswordOver72BytesIs400Not500() throws Exception {
        // 앞 72바이트가 같은 계정이 있어도 truncation 매칭으로 로그인되면 안 된다
        String prefix72 = "a".repeat(72);
        when(userRepository.findByEmail("a@b.com"))
                .thenReturn(Optional.of(User.create("a@b.com", passwordEncoder.encode(prefix72), "닉네임")));

        for (String over : new String[]{"a".repeat(73), "가".repeat(25), "a".repeat(500)}) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.com\",\"password\":\"" + over + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        }
        verify(tokenProvider, never()).generateAccessToken(any(), any());
    }

    @Test
    void signupWithMultibytePasswordOver72BytesIs400Not500() throws Exception {
        // 25자(64자 이하)라 @Size는 통과하지만 UTF-8 75바이트 → 서비스 사전 차단에서 password 필드 오류
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"" + "가".repeat(25)
                                + "\",\"nickname\":\"닉네임\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").value("비밀번호가 너무 깁니다. 영문 기준 64자 이하로 입력해주세요."));
        verify(userRepository, never()).saveAndFlush(any());
    }
}
