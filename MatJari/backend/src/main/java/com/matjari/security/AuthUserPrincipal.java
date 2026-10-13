package com.matjari.security;

import com.matjari.domain.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * JWT 인증 후 SecurityContext에 담기는 인증 주체.
 * 사용자 식별은 반드시 userId 기준으로 한다 (email은 표시/로그용 — 변경 가능 값이라 식별자로 쓰지 않는다).
 * 컨트롤러에서는 {@code @AuthenticationPrincipal AuthUserPrincipal principal}로 받는다.
 * 비밀번호 해시는 담지 않는다 (stateless JWT 인증이라 필요 없음).
 */
public record AuthUserPrincipal(Long userId, String email, Collection<? extends GrantedAuthority> authorities)
        implements Principal {

    public static AuthUserPrincipal of(Long userId, String email) {
        return of(userId, email, UserRole.USER);
    }

    /** DB의 role을 반영한 주체. 권한 문자열은 ROLE_{role} (hasRole("ADMIN")과 매칭). */
    public static AuthUserPrincipal of(Long userId, String email, UserRole role) {
        return new AuthUserPrincipal(userId, email, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    /** DB의 role이 ADMIN인가 (필터가 요청마다 DB에서 읽어 만든 권한 기준 — JWT 클레임이 아니다). */
    public boolean isAdmin() {
        return authorities.stream().anyMatch(a -> ("ROLE_" + UserRole.ADMIN.name()).equals(a.getAuthority()));
    }

    /** Authentication.getName()이 반환하는 값 — 식별자인 userId 문자열 */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
