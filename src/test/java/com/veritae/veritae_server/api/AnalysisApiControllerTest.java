package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.detection.AiDetectionResult;
import com.veritae.veritae_server.openapi.api.AnalysisApiController;
import com.veritae.veritae_server.security.ProblemDetailAuthenticationEntryPoint;
import com.veritae.veritae_server.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalysisApiController.class)
@Import({AnalysisApiDelegateImpl.class, SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class AnalysisApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageAnalysisService imageAnalysisService;

    // JwtAuthenticationFilter 는 @Component(Filter) 라 @WebMvcTest 슬라이스에도 자동 등록되므로,
    // 그 의존성인 JwtTokenProvider 를 만족시켜야 컨텍스트가 뜬다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser
    void analyzeImage_withAuthenticatedMemberAndValidFile_shouldReturn200WithScore() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new AiDetectionResult("spai", 0.87));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDetection.model").value("spai"))
                .andExpect(jsonPath("$.aiDetection.score").value(0.87));
    }

    @Test
    void analyzeImage_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isUnauthorized());
    }
}
