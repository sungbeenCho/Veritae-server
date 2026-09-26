package com.veritae.veritae_server.auth.jwt;

import com.veritae.veritae_server.domain.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private MemberRepository memberRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_withValidTokenOfExistingMember_shouldAuthenticate() throws Exception {
        UUID memberId = UUID.randomUUID();
        when(jwtTokenProvider.parseAndValidateAccessToken("token")).thenReturn(Optional.of(memberId));
        when(memberRepository.existsById(memberId)).thenReturn(true);

        new JwtAuthenticationFilter(jwtTokenProvider, memberRepository)
                .doFilter(requestWithToken("token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(memberId);
    }

    @Test
    void doFilter_withValidTokenOfWithdrawnMember_shouldNotAuthenticate() throws Exception {
        // 탈퇴 후에도 access 토큰은 만료 전까지 서명상 유효하다 - 회원이 없으면 인증하지 않아야
        // 탈퇴한 회원 이름으로 분석 기록이 새로 쌓이지 않는다.
        UUID memberId = UUID.randomUUID();
        when(jwtTokenProvider.parseAndValidateAccessToken("token")).thenReturn(Optional.of(memberId));
        when(memberRepository.existsById(memberId)).thenReturn(false);

        new JwtAuthenticationFilter(jwtTokenProvider, memberRepository)
                .doFilter(requestWithToken("token"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/members/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
