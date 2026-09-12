package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
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

import java.util.List;

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

    @MockitoBean
    private AudioAnalysisService audioAnalysisService;

    @MockitoBean
    private com.veritae.veritae_server.analysis.VideoAnalysisService videoAnalysisService;

    @MockitoBean
    private com.veritae.veritae_server.security.AuthenticatedMemberResolver authenticatedMemberResolver;

    // JwtAuthenticationFilter 는 @Component(Filter) 라 @WebMvcTest 슬라이스에도 자동 등록되므로,
    // 그 의존성인 JwtTokenProvider 를 만족시켜야 컨텍스트가 뜬다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser
    void analyzeImage_withAuthenticatedMemberAndValidFile_shouldReturn200WithScore() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new ImageDetectionResult("spai", 0.87, null));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDetection.model").value("spai"))
                .andExpect(jsonPath("$.aiDetection.score").value(0.87));
    }

    @Test
    @WithMockUser
    void analyzeImage_withFileLargerThanSpringDefaultMultipartLimit_shouldProcessSuccessfully()
            throws Exception {
        // MockMvc는 실제 서블릿 컨테이너의 멀티파트 처리를 거치지 않으므로,
        // 이 테스트는 5MB 파일이 MockMvc 레벨에서 정상 처리됨을 확인할 뿐
        // 프레임워크의 spring.servlet.multipart.max-file-size 설정이 실제로 작동함을 검증하지는 않는다.
        // 설정값 자체(max-file-size=110MB, max-request-size=110MB)는 코드 리뷰로 검증된다.
        // 운영 환경에서 실제 HTTP 요청 시에는 서블릿 컨테이너가 이 설정을 적용한다.
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5MB > Spring Boot 기본 max-file-size(1MB)
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", largeContent);
        when(imageAnalysisService.analyzeImage(any())).thenReturn(new ImageDetectionResult("spai", 0.87, null));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk());
    }

    @Test
    void analyzeImage_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void analyzeAudio_withAuthenticatedMemberAndValidFile_shouldReturn200WithScoreAndEvidence() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());
        when(audioAnalysisService.analyzeAudio(any())).thenReturn(new AudioDetectionResult(
                "antideepfake", 0.87,
                List.of(new Evidence(
                        "시간 구간 이상 패턴", "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                        List.of("temporal"), 0.5, 1.2))));

        mockMvc.perform(multipart("/api/v1/analysis/audio").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDetection.model").value("antideepfake"))
                .andExpect(jsonPath("$.aiDetection.score").value(0.87))
                .andExpect(jsonPath("$.aiDetection.evidence[0].title").value("시간 구간 이상 패턴"))
                .andExpect(jsonPath("$.aiDetection.evidence[0].startSec").value(0.5));
    }

    @Test
    void analyzeAudio_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.wav", "audio/wav", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/audio").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void analyzeVideo_withAuthenticatedMemberAndValidFile_shouldReturn202WithJobId() throws Exception {
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.submitVideo(any(), org.mockito.ArgumentMatchers.eq(memberId))).thenReturn(jobId);

        mockMvc.perform(multipart("/api/v1/analysis/video").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    void analyzeVideo_withoutAuthentication_shouldReturn401() throws Exception {
        var file = new MockMultipartFile("file", "test.mp4", "video/mp4", "fake-bytes".getBytes());

        mockMvc.perform(multipart("/api/v1/analysis/video").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withCompletedJob_shouldReturn200WithResult() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(
                new com.veritae.veritae_server.analysis.AnalysisJobView(
                        jobId,
                        com.veritae.veritae_server.domain.analysisjob.AnalysisJobStatus.COMPLETED,
                        new VideoDetectionResult("dfdc", 0.91,
                                List.of(new Evidence("얼굴 조작 의심 구간", "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                                        List.of("temporal", "face-swap"), 3.0, 7.0)),
                                "base64pngdata"),
                        null,
                        null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.aiDetection.model").value("dfdc"))
                .andExpect(jsonPath("$.aiDetection.evidenceImage").value("base64pngdata"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withNonExistentJob_shouldReturn404() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId))
                .thenThrow(new com.veritae.veritae_server.analysis.AnalysisJobNotFoundException(jobId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAnalysisJob_withoutAuthentication_shouldReturn401() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isUnauthorized());
    }
}
