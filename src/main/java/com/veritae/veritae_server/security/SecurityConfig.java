package com.veritae.veritae_server.security;

import com.veritae.veritae_server.auth.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT 인증(ADR-0001). 로그인은 Spring Security 의 AuthenticationManager 를 거치지 않고
 * {@link com.veritae.veritae_server.auth.AuthService} 에서 직접 처리하므로, 여기서는 보호 엔드포인트에
 * 대한 필터 체인 + 인증 실패 시 RFC 9457 응답만 구성한다. RBAC 이 없는 스펙 범위라 @EnableMethodSecurity
 * 는 추가하지 않았다(사용하지 않는 기능을 미리 열어두지 않음).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] SWAGGER_PATHS = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/openapi.yaml"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(SWAGGER_PATHS).permitAll()
                        .requestMatchers("/api/v1/members/**").authenticated()
                        .requestMatchers("/api/v1/analysis/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problemDetailAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
