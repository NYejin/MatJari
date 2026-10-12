package com.seatswap.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.seatswap.domain.User;
import com.seatswap.domain.UserRole;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.AuthenticationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    void returnsUserResponseForExistingUser() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(User.create("a@b.com", "encoded", "닉네임")));

        UserResponse me = userService.getMe(1L);

        assertThat(me.email()).isEqualTo("a@b.com");
        assertThat(me.nickname()).isEqualTo("닉네임");
        assertThat(me.trustScore()).isEqualTo(0.0);
        assertThat(me.role()).isEqualTo(UserRole.USER);
    }

    @Test
    void missingUserIsAuthenticationFailure() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // AuthenticationException이어야 Security가 401(EntryPoint)로 처리한다
        assertThatThrownBy(() -> userService.getMe(99L))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void looksUpByIdNeverByEmail() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.create("a@b.com", "encoded", "닉네임")));

        userService.getMe(1L);

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
    }


    // ---------------- 닉네임 수정 ----------------

    @Test
    void 닉네임을_바꾸면_정규화한_값이_저장되고_응답에_반영된다() {
        User user = User.create("a@b.com", "encoded", "옛닉네임");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse r = userService.updateNickname(1L, "  새 닉네임 ");

        assertThat(user.getNickname()).isEqualTo("새 닉네임");
        assertThat(r.nickname()).isEqualTo("새 닉네임");
    }

    @Test
    void 같은_값이면_멱등이고_중복_닉네임도_허용한다() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        assertThat(userService.updateNickname(1L, "닉네임").nickname()).isEqualTo("닉네임");
        verify(userRepository, never()).existsByEmail(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 길이_공백_제어문자는_nickname_필드_오류이고_저장하지_않는다() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        for (String bad : new String[]{null, "", "   ", "가", "가".repeat(21), "​​​", "닉네임", "ㅤㅤ"}) {
            assertThatThrownBy(() -> userService.updateNickname(1L, bad))
                    .isInstanceOfSatisfying(com.seatswap.exception.FieldValidationException.class,
                            e -> assertThat(e.getField()).isEqualTo("nickname"));
        }
        assertThat(user.getNickname()).isEqualTo("닉네임");
        assertThat(userService.updateNickname(1L, "가나").nickname()).isEqualTo("가나"); // 경계 2자
        assertThat(userService.updateNickname(1L, "가".repeat(20)).nickname()).hasSize(20); // 경계 20자
    }

    @Test
    void 없는_사용자의_닉네임_수정은_인증_실패다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.updateNickname(99L, "닉네임"))
                .isInstanceOf(AuthenticationException.class);
    }

    // ---------------- 응답: 자리표시 이메일 / 비밀번호 로그인 여부 ----------------

    @Test
    void 자리표시_이메일은_원문을_내리지_않고_emailPlaceholder로_알린다() {
        User social = User.createSocial("kakao.0123456789abcdef@social.seatswap.invalid", "unknown", "카카오닉");
        when(userRepository.findById(1L)).thenReturn(Optional.of(social));
        UserResponse r = userService.getMe(1L);
        assertThat(r.email()).isNull();
        assertThat(r.emailPlaceholder()).isTrue();
        assertThat(r.passwordLoginEnabled()).isFalse();
    }

    @Test
    void 일반_가입과_이메일이_있는_소셜_가입의_응답() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(User.create("a@b.com", "encoded", "닉네임")));
        UserResponse normal = userService.getMe(1L);
        assertThat(normal.email()).isEqualTo("a@b.com");
        assertThat(normal.emailPlaceholder()).isFalse();
        assertThat(normal.passwordLoginEnabled()).isTrue();

        when(userRepository.findById(2L)).thenReturn(Optional.of(User.createSocial("s@b.com", "unknown", "소셜닉")));
        UserResponse social = userService.getMe(2L);
        assertThat(social.email()).isEqualTo("s@b.com");
        assertThat(social.emailPlaceholder()).isFalse();
        assertThat(social.passwordLoginEnabled()).isFalse();
    }
}
