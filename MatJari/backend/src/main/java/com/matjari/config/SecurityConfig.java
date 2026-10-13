package com.matjari.config;

import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    /** REST CORS 와 WebSocket 핸드셰이크가 같은 Origin 목록(matjari.websocket.allowed-origins)을 쓴다. */
    @org.springframework.beans.factory.annotation.Value("${matjari.websocket.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins = {"http://localhost:5173"};

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JwtAuthenticationFilter는 @Component라 Spring Boot가 서블릿 필터로도 자동 등록한다.
     * 시큐리티 체인 안(addFilterBefore)에서만 동작해야 하므로 서블릿 레벨 자동 등록은 끈다.
     * (켜져 있으면 /api/auth/** 등 체인 밖 순서에서도 한 번 더 실행될 수 있음)
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // FR-01: 회원가입/로그인/토큰재발급은 인증 없이 허용
                        .requestMatchers("/api/auth/**").permitAll()
                        // WebSocket(STOMP) 핸드셰이크 — 인증은 STOMP CONNECT 프레임(StompAuthChannelInterceptor)에서 한다
                        .requestMatchers("/ws/**").permitAll()
                        // 예외 발생 시 서블릿 컨테이너가 /error로 포워드하는데, 여기서 막히면 원래 오류가
                        // 403 빈 바디로 둔갑한다. 오류 응답 자체는 인증 없이 내려가야 한다.
                        .requestMatchers("/error").permitAll()
                        // 관리자 전용 (role은 매 요청 DB에서 로드 — JwtAuthenticationFilter). 일반 사용자는 403
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // 미인증 → 401 {"message":"로그인이 필요합니다."}, 권한 없음 → 403 {"message":"접근 권한이 없습니다."}
                // (기본값은 Http403ForbiddenEntryPoint라 미인증도 403 빈 바디였다)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 개발 환경 기준 CORS 허용 목록. 배포 단계에서 실제 프론트 도메인으로 교체 필요
     * (09_배포URL 확정 시 doc-writer에게 갱신 요청).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(WebSocketConfig.validatedOrigins(allowedOrigins)));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
