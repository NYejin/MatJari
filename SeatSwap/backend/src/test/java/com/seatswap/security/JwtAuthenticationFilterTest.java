package com.seatswap.security;

import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private CustomUserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(JwtTokenProvider.class);
        userDetailsService = mock(CustomUserDetailsService.class);
        filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService, mock(SecurityErrorResponseWriter.class));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 체인 다음 단계에서 본 SecurityContext의 인증 정보를 캡처한다. */
    private Authentication runFilter(String authorizationHeader, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tickets");
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                chainCalled.set(true);
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        filter.doFilter(request, response, chain);
        assertThat(chainCalled.get()).as("filter must always continue the chain").isTrue();
        return seen.get();
    }

    @Test
    void validAccessTokenAuthenticates() throws Exception {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));

        Authentication auth = runFilter("Bearer good", new MockHttpServletResponse());

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthUserPrincipal.class);
        AuthUserPrincipal principal = (AuthUserPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("a@b.com");
        assertThat(auth.getName()).isEqualTo("1");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void deletedUserTokenLeavesContextEmptyAndDoesNotWriteResponse() throws Exception {
        when(tokenProvider.validateToken("ghost")).thenReturn(true);
        when(tokenProvider.isRefreshToken("ghost")).thenReturn(false);
        when(tokenProvider.getUserId("ghost")).thenReturn(99L);
        when(userDetailsService.loadUserById(99L))
                .thenThrow(new UsernameNotFoundException("not found"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication auth = runFilter("Bearer ghost", response);

        assertThat(auth).isNull();
        // 필터는 응답을 직접 쓰지 않는다 (401은 EntryPoint 책임)
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void jwtParsingErrorLeavesContextEmpty() throws Exception {
        when(tokenProvider.validateToken("weird")).thenReturn(true);
        when(tokenProvider.isRefreshToken("weird")).thenThrow(new MalformedJwtException("bad"));

        assertThat(runFilter("Bearer weird", new MockHttpServletResponse())).isNull();
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() throws Exception {
        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.isRefreshToken("refresh")).thenReturn(true);

        assertThat(runFilter("Bearer refresh", new MockHttpServletResponse())).isNull();
        verify(userDetailsService, never()).loadUserById(any());
    }

    @Test
    void invalidOrMissingTokenLeavesContextEmpty() throws Exception {
        when(tokenProvider.validateToken("expired")).thenReturn(false);

        assertThat(runFilter("Bearer expired", new MockHttpServletResponse())).isNull();
        assertThat(runFilter(null, new MockHttpServletResponse())).isNull();
        assertThat(runFilter("Basic abc", new MockHttpServletResponse())).isNull();
    }

    @Test
    void infrastructureErrorsAreNotDisguisedAs401() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> runFilter("Bearer good", new MockHttpServletResponse()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void authenticationServiceExceptionIsNotSwallowed() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L))
                .thenThrow(new AuthenticationServiceException("user store failure"));

        assertThatThrownBy(() -> runFilter("Bearer good", new MockHttpServletResponse()))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void dataAnomalyIllegalArgumentIsNotSwallowed() {
        when(tokenProvider.validateToken("odd")).thenReturn(true);
        when(tokenProvider.isRefreshToken("odd")).thenReturn(false);
        // sub가 숫자가 아닌 등 데이터 이상 (Long.valueOf → NumberFormatException)
        when(tokenProvider.getUserId("odd")).thenThrow(new NumberFormatException("For input string: \"abc\""));

        assertThatThrownBy(() -> runFilter("Bearer odd", new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiesUserByTokenUserIdNotByEmailClaim() throws Exception {
        // 이메일 변경 시나리오: 토큰(sub=1)의 email 클레임은 옛 주소 old@b.com이고,
        // 지금 old@b.com은 다른 사용자(id=2)가 쓰고 있다. 인증 주체는 반드시 토큰의 userId(1)여야 한다.
        when(tokenProvider.validateToken("stale-email")).thenReturn(true);
        when(tokenProvider.isRefreshToken("stale-email")).thenReturn(false);
        when(tokenProvider.getUserId("stale-email")).thenReturn(1L);
        when(tokenProvider.getEmail("stale-email")).thenReturn("old@b.com");
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "new@b.com"));
        when(userDetailsService.loadUserByUsername("old@b.com")).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername("old@b.com")
                        .password("pw").roles("USER").build()); // id=2 사용자 — 쓰이면 안 됨

        Authentication auth = runFilter("Bearer stale-email", new MockHttpServletResponse());

        AuthUserPrincipal principal = (AuthUserPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("new@b.com");
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(tokenProvider, never()).getEmail(anyString());
    }
}
