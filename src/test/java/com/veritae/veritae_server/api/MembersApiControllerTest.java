package com.veritae.veritae_server.api;

import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberService;
import com.veritae.veritae_server.openapi.api.MembersApiController;
import com.veritae.veritae_server.security.AuthenticatedMemberResolver;
import com.veritae.veritae_server.security.ProblemDetailAuthenticationEntryPoint;
import com.veritae.veritae_server.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MembersApiController.class)
@Import({MembersApiDelegateImpl.class, SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class MembersApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private AuthenticatedMemberResolver authenticatedMemberResolver;

    // JwtAuthenticationFilter 는 @Component(Filter) 라 @WebMvcTest 슬라이스에도 자동 등록되므로,
    // 그 의존성인 JwtTokenProvider 를 만족시켜야 컨텍스트가 뜬다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private com.veritae.veritae_server.domain.member.MemberRepository memberRepository;

    @MockitoBean
    private com.veritae.veritae_server.withdrawal.WithdrawalService withdrawalService;

    @Test
    @WithMockUser
    void withdraw_withCorrectPassword_shouldReturn204() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);

        mockMvc.perform(post("/api/v1/members/me/withdrawal")
                        .contentType("application/json").content("{\"password\":\"veritae123\"}"))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(withdrawalService).withdraw(memberId, "veritae123");
    }

    @Test
    @WithMockUser
    void withdraw_withWrongPassword_shouldReturn400InvalidPassword() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        org.mockito.Mockito.doThrow(new com.veritae.veritae_server.withdrawal.InvalidPasswordException())
                .when(withdrawalService).withdraw(memberId, "wrong");

        mockMvc.perform(post("/api/v1/members/me/withdrawal")
                        .contentType("application/json").content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PASSWORD"));
    }

    @Test
    @WithMockUser
    void withdraw_withoutPassword_shouldReturn400ValidationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/members/me/withdrawal")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("password"));
        org.mockito.Mockito.verifyNoInteractions(withdrawalService);
    }

    @Test
    void withdraw_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/members/me/withdrawal")
                        .contentType("application/json").content("{\"password\":\"veritae123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getMyProfile_withAuthenticatedMember_shouldReturn200WithMemberResponse() throws Exception {
        Member member = Member.register("user@veritae.app", "encoded-hash", "진실이");
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(member.getId());
        when(memberService.getById(member.getId())).thenReturn(member);

        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.email").value("user@veritae.app"))
                .andExpect(jsonPath("$.nickname").value("진실이"));
    }
}
