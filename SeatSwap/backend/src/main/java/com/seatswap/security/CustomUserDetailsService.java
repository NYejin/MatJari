package com.seatswap.security;

import com.seatswap.domain.User;
import com.seatswap.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 인증 사용자 로딩.
 * JWT 인증 경로는 {@link #loadUserById(Long)}(토큰 sub = userId)만 사용한다.
 * 토큰에는 role을 넣지 않는다 — 매 요청 DB에서 사용자를 로드할 때 role을 읽는다(권한 변경이 즉시 반영됨).
 * UserDetailsService 구현은 유지한다 — 이 빈이 없으면 Spring Boot가 기본 in-memory 사용자
 * (generated security password)를 자동 생성하기 때문. email 기반 loadUserByUsername은
 * 현재 인증 경로에서 쓰지 않는다 (폼/Basic 로그인 미사용).
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AccountSuspensionChecker suspensionChecker;

    /**
     * JWT 인증용. 사용자 없음 → UsernameNotFoundException (필터가 잡아 미인증 처리 → 401).
     * 활성 이용 정지가 있으면 AccountSuspendedException (필터가 403 ACCOUNT_SUSPENDED 로 응답) — 모든 인증 요청과 STOMP CONNECT 가 여기를 거친다.
     */
    @Transactional(readOnly = true)
    public AuthUserPrincipal loadUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: id=" + userId));
        if (user.isWithdrawn()) {
            // 탈퇴한 계정: 기존 액세스·리프레시 토큰이 즉시 무효가 된다(필터 401, STOMP CONNECT 거부)
            throw new UsernameNotFoundException("탈퇴한 사용자입니다: id=" + userId);
        }
        suspensionChecker.assertNotSuspended(user.getId());
        return AuthUserPrincipal.of(user.getId(), user.getEmail(), user.getRole());
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
