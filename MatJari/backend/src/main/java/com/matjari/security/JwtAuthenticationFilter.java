package com.matjari.security;

import com.matjari.exception.AccountSuspendedException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authorization: Bearer {accessToken} 헤더를 읽어 SecurityContext에 인증 정보(AuthUserPrincipal)를 채운다.
 * 액세스 토큰만 일반 API 를 연다(refresh·탈퇴 전용 토큰은 type 클레임이 달라 거절). 탈퇴 전용 토큰은 정지 중 로그인이 주는 단기 토큰이며
 * DELETE /api/users/me 에서만 인증으로 인정한다.
 * 토큰이 없거나 무효이면 인증 정보를 채우지 않고 통과시킨다 — 401 응답은 JwtAuthenticationEntryPoint 책임.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final SecurityErrorResponseWriter errorWriter;

    /** 이용 정지 중에도 허용하는 유일한 경로(정지된 사용자가 스스로 로그아웃할 수 있어야 한다). */
    static final String LOGOUT_PATH = "/api/auth/logout";
    /** 이용 정지 중에도 허용하는 또 하나의 경로(회원탈퇴, DELETE만). */
    static final String WITHDRAW_PATH = "/api/users/me";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader(HEADER);

        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                boolean valid = jwtTokenProvider.validateToken(token);
                if (valid && jwtTokenProvider.isWithdrawToken(token)) {
                    if (isWithdraw(request)) {
                        authenticate(request, userDetailsService.loadUserByIdAllowingSuspended(jwtTokenProvider.getUserId(token)));
                    }
                } else if (valid && !jwtTokenProvider.isRefreshToken(token)) {
                    // 식별은 토큰 sub(userId) 기준 — email 클레임은 변경될 수 있어 식별자로 쓰지 않는다
                    Long userId = jwtTokenProvider.getUserId(token);
                    AuthUserPrincipal principal = userDetailsService.loadUserById(userId);

                    authenticate(request, principal);
                }
            } catch (AccountSuspendedException e) {
                // 이용 정지: 401 이 아니라 403 ACCOUNT_SUSPENDED (프론트가 토큰 재발급을 시도하지 않게 구분).
                // 로그아웃은 미인증으로 통과시키고, 회원탈퇴(DELETE /api/users/me)만 인증된 채로 통과시킨다(개인정보 삭제 요구를 막지 않는다).
                SecurityContextHolder.clearContext();
                if (isWithdraw(request)) {
                    try {
                        Long userId = jwtTokenProvider.getUserId(token);
                        AuthUserPrincipal principal = userDetailsService.loadUserByIdAllowingSuspended(userId);
                        authenticate(request, principal);
                    } catch (UsernameNotFoundException ignored) {
                        // 그 사이 탈퇴됨 -> 미인증(401)
                    }
                } else if (!isLogout(request)) {
                    errorWriter.writeSuspended(response, e);
                    return;
                }
            } catch (UsernameNotFoundException | JwtException e) {
                // 토큰은 서명상 유효하지만 사용자가 삭제된 경우(UsernameNotFoundException) 등.
                // 여기서 직접 응답을 쓰지 않고 "인증 안 됨" 상태로 체인을 계속 진행한다.
                // → 보호 API면 AuthorizationFilter → EntryPoint(401), /api/auth/** 등 permitAll이면 정상 처리.
                // catch 범위는 "토큰은 왔지만 인증 불가"인 두 경우로 한정한다(사용자 없음, JWT 파싱 실패).
                // DB 장애·AuthenticationServiceException·데이터 이상(IllegalArgumentException 등)은 401로 숨기지 않고 전파한다.
                log.debug("JWT authentication skipped: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private static void authenticate(HttpServletRequest request, AuthUserPrincipal principal) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static boolean isLogout(HttpServletRequest request) {
        return LOGOUT_PATH.equals(pathOf(request));
    }

    private static boolean isWithdraw(HttpServletRequest request) {
        return "DELETE".equals(request.getMethod()) && WITHDRAW_PATH.equals(pathOf(request));
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
