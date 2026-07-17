package com.veritae.veritae_server.auth;

import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 로그인/토큰 갱신 흐름을 담당한다. Member 엔티티 자체가 소유한 이메일 유일성 같은 회원 도메인
 * 불변식과 달리, "자격 증명이 올바른가"/"토큰이 유효한가"는 인증 흐름의 관심사이므로 MemberService 가
 * 아닌 이 서비스에 둔다.
 *
 * <p>Spring Security 의 {@code AuthenticationManager}/{@code UserDetailsService} 경로를 타지 않고
 * 직접 {@link PasswordEncoder#matches}로 검증한다 — AC-2 가 요구하는 "이메일 미존재/비밀번호 불일치를
 * 구분하지 않고 동일한 401" 을 한 곳에서 보장하기 위한 의도적인 선택이다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * 존재하지 않는 이메일로 로그인 시도 시에도 동일한 비용의 BCrypt 비교를 수행시켜, "이메일 없음"과
     * "비밀번호 불일치" 사이의 응답 시간 차이(계정 존재 여부 추측 단서)를 없애기 위한 고정 더미 해시.
     * 실제 회원 데이터와 무관한 임의 값의 해시이며, 이 값 자체가 로그인에 성공할 일은 없다.
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$12$SKHqHkOkZMDxQV/TfxB9Su8zN02dShFHgxixs3WEv6YlQdl2VO3i2";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional(readOnly = true)
    public IssuedTokens login(String email, String rawPassword) {
        Member member = memberRepository.findByEmail(email).orElse(null);
        String hashToCompare = member != null ? member.getPasswordHash() : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCompare);
        if (member == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtTokenProvider.generateAccessToken(member.getId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(member.getId());
        return new IssuedTokens(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpirySeconds());
    }

    @Transactional(readOnly = true)
    public RefreshedAccessToken refresh(String refreshToken) {
        UUID memberId = jwtTokenProvider.parseAndValidateRefreshToken(refreshToken)
                .orElseThrow(InvalidRefreshTokenException::new);
        if (!memberRepository.existsById(memberId)) {
            throw new InvalidRefreshTokenException();
        }

        String accessToken = jwtTokenProvider.generateAccessToken(memberId);
        return new RefreshedAccessToken(accessToken, jwtTokenProvider.getAccessTokenExpirySeconds());
    }
}
