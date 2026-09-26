package com.veritae.veritae_server.api;

import com.veritae.veritae_server.auth.AuthService;
import com.veritae.veritae_server.auth.InvalidCredentialsException;
import com.veritae.veritae_server.auth.IssuedTokens;
import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.domain.member.EmailAlreadyExistsException;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberService;
import com.veritae.veritae_server.openapi.api.AuthApiController;
import com.veritae.veritae_server.security.ProblemDetailAuthenticationEntryPoint;
import com.veritae.veritae_server.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig 를 실제로 import 해 /api/v1/auth/** permitAll + CSRF 비활성화 규칙까지 그대로 검증한다.
// (@WebMvcTest 기본 시큐리티 자동설정은 우리 SecurityConfig 를 모르는 채 모든 POST 를 CSRF 로 막는다)
@WebMvcTest(controllers = AuthApiController.class)
@Import({AuthApiDelegateImpl.class, SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class AuthApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private AuthService authService;

    // JwtAuthenticationFilter 는 @Component(Filter) 라 @WebMvcTest 슬라이스에도 자동 등록되므로,
    // 그 의존성인 JwtTokenProvider 를 만족시켜야 컨텍스트가 뜬다(실제 토큰 검증 로직은 이 테스트의 관심사가 아님).
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 가 토큰의 회원이 아직 존재하는지(탈퇴 여부) 확인하는 데 쓴다.
    @MockitoBean
    private com.veritae.veritae_server.domain.member.MemberRepository memberRepository;

    @Test
    void signup_withValidRequest_shouldReturn201WithMemberResponse() throws Exception {
        Member member = Member.register("user@veritae.app", "encoded-hash", "진실이");
        when(memberService.signup(anyString(), anyString(), anyString())).thenReturn(member);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@veritae.app","password":"veritae123","nickname":"진실이"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.email").value("user@veritae.app"))
                .andExpect(jsonPath("$.nickname").value("진실이"));
    }

    @Test
    void signup_withInvalidPassword_shouldReturn400WithViolations() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@veritae.app","password":"short","nickname":"진실이"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations[0].field").value("password"));
    }

    @Test
    void signup_withDuplicateEmail_shouldReturn409() throws Exception {
        when(memberService.signup(anyString(), anyString(), anyString()))
                .thenThrow(new EmailAlreadyExistsException());

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@veritae.app","password":"veritae123","nickname":"진실이"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void login_withValidCredentials_shouldReturn200WithTokens() throws Exception {
        when(authService.login("user@veritae.app", "veritae123"))
                .thenReturn(new IssuedTokens("access-token", "refresh-token", 1800L));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@veritae.app","password":"veritae123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    void signup_withMalformedJsonBody_shouldStillReturnProblemDetailWithErrorCode() throws Exception {
        // 우리가 명시적으로 @ExceptionHandler 하지 않은 경로(HttpMessageNotReadableException)도
        // GlobalExceptionHandler.handleExceptionInternal 오버라이드를 거쳐 errorCode/timestamp 를
        // 반드시 갖도록 한 수정(M1)을 검증한다.
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void login_withInvalidCredentials_shouldReturn401() throws Exception {
        when(authService.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@veritae.app","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }
}
