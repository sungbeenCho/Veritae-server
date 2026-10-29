package com.veritae.veritae_server.ratelimit;

import com.veritae.veritae_server.api.AuthApiDelegateImpl;
import com.veritae.veritae_server.auth.AuthService;
import com.veritae.veritae_server.auth.IssuedTokens;
import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.domain.member.MemberRepository;
import com.veritae.veritae_server.domain.member.MemberService;
import com.veritae.veritae_server.openapi.api.AuthApiController;
import com.veritae.veritae_server.security.ProblemDetailAuthenticationEntryPoint;
import com.veritae.veritae_server.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그인 횟수 제한이 실제 요청 흐름에서 429 + Retry-After + ProblemDetail로 나가는지 확인한다. */
@WebMvcTest(controllers = AuthApiController.class)
@Import({AuthApiDelegateImpl.class, SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
@TestPropertySource(properties = {"rate-limit.login.max-requests=2", "rate-limit.login.window=1m"})
class RateLimitWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        when(authService.login(anyString(), anyString())).thenReturn(new IssuedTokens("a", "r", 1800L));
    }

    @Test
    void login_overLimitFromSameIp_shouldReturn429WithRetryAfter() throws Exception {
        login("10.0.0.1").andExpect(status().isOk());
        login("10.0.0.1").andExpect(status().isOk());

        login("10.0.0.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.detail").value(RateLimitExceededException.USER_MESSAGE));
    }

    @Test
    void login_fromAnotherIp_shouldNotBeAffected() throws Exception {
        login("10.0.0.2").andExpect(status().isOk());
        login("10.0.0.2").andExpect(status().isOk());
        login("10.0.0.2").andExpect(status().isTooManyRequests());

        login("10.0.0.3").andExpect(status().isOk());
    }

    private ResultActions login(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"user@veritae.app","password":"veritae123"}
                        """));
    }
}
