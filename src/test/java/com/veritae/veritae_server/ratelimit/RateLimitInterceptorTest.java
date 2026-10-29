package com.veritae.veritae_server.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitInterceptorTest {

    private static final RateLimitProperties.Rule GENEROUS = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(
            new FixedWindowRateLimiter(Clock.systemUTC()),
            new RateLimitProperties(GENEROUS, GENEROUS, GENEROUS, new RateLimitProperties.Rule(2, Duration.ofHours(1))));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analysis_overLimitForSameMember_shouldBeRejectedAcrossImageAudioVideo() throws Exception {
        // 이미지·음성·영상은 GPU를 같이 쓰므로 회원 한 명의 분석 횟수를 합쳐서 센다.
        loginAs(UUID.randomUUID());
        assertThat(handle("POST", "/api/v1/analysis/image")).isTrue();
        assertThat(handle("POST", "/api/v1/analysis/audio")).isTrue();

        assertThatThrownBy(() -> handle("POST", "/api/v1/analysis/video"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds()).isPositive());
    }

    @Test
    void analysis_ofAnotherMember_shouldNotBeAffected() throws Exception {
        loginAs(UUID.randomUUID());
        handle("POST", "/api/v1/analysis/image");
        handle("POST", "/api/v1/analysis/image");

        loginAs(UUID.randomUUID());
        assertThat(handle("POST", "/api/v1/analysis/image")).isTrue();
    }

    @Test
    void readOnlyRequests_shouldNotBeCounted() throws Exception {
        loginAs(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            assertThat(handle("GET", "/api/v1/analysis/records")).isTrue();
            assertThat(handle("GET", "/api/v1/analysis/jobs/" + UUID.randomUUID())).isTrue();
        }
    }

    private void loginAs(UUID memberId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberId, null, List.of()));
    }

    private boolean handle(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
