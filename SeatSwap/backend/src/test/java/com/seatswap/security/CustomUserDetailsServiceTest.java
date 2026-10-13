package com.seatswap.security;

import com.seatswap.domain.User;
import com.seatswap.domain.UserRole;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomUserDetailsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(userRepository, mock(AccountSuspensionChecker.class));

    @Test
    void loadUserByIdBuildsUserIdBasedPrincipal() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        AuthUserPrincipal principal = service.loadUserById(7L);

        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(principal.getName()).isEqualTo("7");
        assertThat(principal.authorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void adminRoleFromDbIsReflectedInAuthorities() {
        User admin = User.create("admin@b.com", "encoded", "관리자");
        ReflectionTestUtils.setField(admin, "id", 1L);
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN); // DB에서 수동 부여한 상황
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findByEmail("admin@b.com")).thenReturn(Optional.of(admin));

        assertThat(service.loadUserById(1L).authorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(service.loadUserByUsername("admin@b.com").getAuthorities())
                .extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsernameUsesUserRoleForNormalUser() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

        assertThat(service.loadUserByUsername("a@b.com").getAuthorities())
                .extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void withdrawnUserIsRejectedLikeMissingUserEvenWithValidToken() {
        User user = User.create("a@b.com", "encoded", "닉네임");
        ReflectionTestUtils.setField(user, "id", 8L);
        user.anonymizeForWithdrawal("randomhash", java.time.LocalDateTime.of(2026, 10, 13, 12, 0));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.loadUserById(8L)).isInstanceOf(UsernameNotFoundException.class);
        assertThat(user.getEmail()).isEqualTo("withdrawn.8@withdrawn.seatswap.invalid");
    }

    @Test
    void missingUserThrowsUsernameNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // 필터가 이 예외만 잡아 미인증(→401)으로 처리한다
        assertThatThrownBy(() -> service.loadUserById(99L)).isInstanceOf(UsernameNotFoundException.class);
    }
}
