package com.veritae.veritae_server.auth;

import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(memberRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void login_withCorrectCredentials_shouldReturnIssuedTokens() {
        // Given
        Member member = Member.register("user@veritae.app", "encoded-hash", "진실이");
        when(memberRepository.findByEmail("user@veritae.app")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("veritae123", "encoded-hash")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(member.getId())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(member.getId())).thenReturn("refresh-token");
        when(jwtTokenProvider.getAccessTokenExpirySeconds()).thenReturn(1800L);

        // When
        IssuedTokens tokens = authService.login("user@veritae.app", "veritae123");

        // Then
        assertThat(tokens.accessToken()).isEqualTo("access-token");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
        assertThat(tokens.expiresInSeconds()).isEqualTo(1800L);
    }

    @Test
    void login_withUnknownEmail_shouldThrowInvalidCredentialsException() {
        // Given
        when(memberRepository.findByEmail("unknown@veritae.app")).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> authService.login("unknown@veritae.app", "veritae123"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_withUnknownEmail_shouldStillCallPasswordEncoderToAvoidTimingLeak() {
        // 이메일이 없다고 BCrypt 비교를 건너뛰면, 실제 회원 조회 여부가 응답 시간 차이로 새어나간다(M-minor).
        // 이 테스트는 "이메일 없음" 경로에서도 passwordEncoder.matches 가 호출됨을 고정한다.
        when(memberRepository.findByEmail("unknown@veritae.app")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("unknown@veritae.app", "veritae123"))
                .isInstanceOf(InvalidCredentialsException.class);

        org.mockito.Mockito.verify(passwordEncoder).matches(eq("veritae123"), any(String.class));
    }

    @Test
    void login_withWrongPassword_shouldThrowInvalidCredentialsException() {
        // Given
        Member member = Member.register("user@veritae.app", "encoded-hash", "진실이");
        when(memberRepository.findByEmail("user@veritae.app")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("wrong-password", "encoded-hash")).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> authService.login("user@veritae.app", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_withValidRefreshToken_shouldReturnNewAccessToken() {
        // Given
        UUID memberId = UUID.randomUUID();
        when(jwtTokenProvider.parseAndValidateRefreshToken("valid-refresh-token")).thenReturn(Optional.of(memberId));
        when(memberRepository.existsById(memberId)).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(memberId)).thenReturn("new-access-token");
        when(jwtTokenProvider.getAccessTokenExpirySeconds()).thenReturn(1800L);

        // When
        RefreshedAccessToken result = authService.refresh("valid-refresh-token");

        // Then
        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.expiresInSeconds()).isEqualTo(1800L);
    }

    @Test
    void refresh_withInvalidRefreshToken_shouldThrowInvalidRefreshTokenException() {
        // Given
        when(jwtTokenProvider.parseAndValidateRefreshToken("bad-token")).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> authService.refresh("bad-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_whenMemberNoLongerExists_shouldThrowInvalidRefreshTokenException() {
        // Given
        UUID memberId = UUID.randomUUID();
        when(jwtTokenProvider.parseAndValidateRefreshToken("valid-refresh-token")).thenReturn(Optional.of(memberId));
        when(memberRepository.existsById(memberId)).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> authService.refresh("valid-refresh-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
