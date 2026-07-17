package com.veritae.veritae_server.auth.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String TEST_SECRET = "K3gpPxv0vu8NeMQK7rQuO878MGpepjENfDjKqsS2L2Q=";

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(new JwtProperties(TEST_SECRET, 1800L, 1_209_600L));
        jwtTokenProvider.init();
    }

    @Test
    void generateAccessToken_thenParseAndValidateAccessToken_shouldReturnSameMemberId() {
        UUID memberId = UUID.randomUUID();

        String accessToken = jwtTokenProvider.generateAccessToken(memberId);
        Optional<UUID> parsed = jwtTokenProvider.parseAndValidateAccessToken(accessToken);

        assertThat(parsed).contains(memberId);
    }

    @Test
    void generateRefreshToken_thenParseAndValidateRefreshToken_shouldReturnSameMemberId() {
        UUID memberId = UUID.randomUUID();

        String refreshToken = jwtTokenProvider.generateRefreshToken(memberId);
        Optional<UUID> parsed = jwtTokenProvider.parseAndValidateRefreshToken(refreshToken);

        assertThat(parsed).contains(memberId);
    }

    @Test
    void parseAndValidateAccessToken_givenRefreshToken_shouldReturnEmpty() {
        UUID memberId = UUID.randomUUID();
        String refreshToken = jwtTokenProvider.generateRefreshToken(memberId);

        Optional<UUID> parsed = jwtTokenProvider.parseAndValidateAccessToken(refreshToken);

        assertThat(parsed).isEmpty();
    }

    @Test
    void parseAndValidateAccessToken_givenTamperedToken_shouldReturnEmpty() {
        UUID memberId = UUID.randomUUID();
        String accessToken = jwtTokenProvider.generateAccessToken(memberId);
        String tampered = accessToken.substring(0, accessToken.length() - 1)
                + (accessToken.charAt(accessToken.length() - 1) == 'a' ? 'b' : 'a');

        Optional<UUID> parsed = jwtTokenProvider.parseAndValidateAccessToken(tampered);

        assertThat(parsed).isEmpty();
    }

    @Test
    void parseAndValidateAccessToken_givenGarbageInput_shouldReturnEmpty() {
        Optional<UUID> parsed = jwtTokenProvider.parseAndValidateAccessToken("not-a-jwt");

        assertThat(parsed).isEmpty();
    }

    @Test
    void init_whenSecretIsBlank_shouldGenerateWorkingRandomKeyInsteadOfFailing() {
        // JWT_SECRET 미설정 시 커밋된 고정 키로 폴백하지 않고, 유효한 임시 키를 생성해 정상 동작해야 한다.
        JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties("", 1800L, 1_209_600L));
        provider.init();
        UUID memberId = UUID.randomUUID();

        String accessToken = provider.generateAccessToken(memberId);
        Optional<UUID> parsed = provider.parseAndValidateAccessToken(accessToken);

        assertThat(parsed).contains(memberId);
    }

    @Test
    void init_calledTwiceWithBlankSecret_shouldGenerateDifferentKeysPerProcess() {
        // 매 프로세스(=매 init 호출)마다 다른 임시 키가 나와야 한다 - 그래야 재시작 시 기존 토큰이
        // 실제로 무효화되고(의도된 안전장치), 고정된 사실상의 "기본 키"가 생기지 않는다.
        JwtTokenProvider first = new JwtTokenProvider(new JwtProperties("", 1800L, 1_209_600L));
        first.init();
        JwtTokenProvider second = new JwtTokenProvider(new JwtProperties("", 1800L, 1_209_600L));
        second.init();
        UUID memberId = UUID.randomUUID();

        String tokenFromFirst = first.generateAccessToken(memberId);

        assertThat(second.parseAndValidateAccessToken(tokenFromFirst)).isEmpty();
    }
}
