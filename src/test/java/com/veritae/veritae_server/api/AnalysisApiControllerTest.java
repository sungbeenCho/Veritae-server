package com.veritae.veritae_server.api;

import com.veritae.veritae_server.analysis.AnalysisMediaService;
import com.veritae.veritae_server.analysis.AnalysisOutcome;
import com.veritae.veritae_server.analysis.AudioAnalysisService;
import com.veritae.veritae_server.analysis.ImageAnalysisService;
import com.veritae.veritae_server.auth.jwt.JwtTokenProvider;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionResult;
import com.veritae.veritae_server.detection.ScamEvidence;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.media.MediaObject;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @MockitoBean
    private com.veritae.veritae_server.analysis.AnalysisHistoryService analysisHistoryService;

    @MockitoBean
    private AnalysisMediaService analysisMediaService;

    // JwtAuthenticationFilter 가 토큰의 회원이 아직 존재하는지(탈퇴 여부) 확인하는 데 쓴다.
    @MockitoBean
    private com.veritae.veritae_server.domain.member.MemberRepository memberRepository;

    // JwtAuthenticationFilter 는 @Component(Filter) 라 @WebMvcTest 슬라이스에도 자동 등록되므로,
    // 그 의존성인 JwtTokenProvider 를 만족시켜야 컨텍스트가 뜬다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @WithMockUser
    void analyzeImage_withAuthenticatedMemberAndValidFile_shouldReturn200WithScore() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(imageAnalysisService.analyzeImage(any(), any()))
                .thenReturn(new AnalysisOutcome<>(java.util.UUID.randomUUID(), new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null)));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiDetection.model").value("spai"))
                .andExpect(jsonPath("$.aiDetection.score").value(0.87));
    }

    @Test
    @WithMockUser
    void analyzeImage_withScamDetection_shouldIncludeScamDetectionInResponse() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        var scamDetection = new ScamDetectionResult("lilju", 0.82, List.of(new ScamEvidence("계좌번호를 알려주세요", 0.95)));
        when(imageAnalysisService.analyzeImage(any(), any()))
                .thenReturn(new AnalysisOutcome<>(java.util.UUID.randomUUID(), new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), scamDetection)));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scamDetection.model").value("lilju"))
                .andExpect(jsonPath("$.scamDetection.score").value(0.82))
                .andExpect(jsonPath("$.scamDetection.evidence[0].sentence").value("계좌번호를 알려주세요"));
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
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(imageAnalysisService.analyzeImage(any(), any()))
                .thenReturn(new AnalysisOutcome<>(java.util.UUID.randomUUID(), new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null)));

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
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(audioAnalysisService.analyzeAudio(any(), any())).thenReturn(new AnalysisOutcome<>(java.util.UUID.randomUUID(),
                new AudioAnalysisResult(new AudioDetectionResult(
                        "antideepfake", 0.87,
                        List.of(new Evidence(
                                "시간 구간 이상 패턴", "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                                List.of("temporal"), 0.5, 1.2))), null)));

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
                        com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.COMPLETED,
                        new VideoAnalysisResult(new VideoDetectionResult("dfdc", 0.91,
                                List.of(new Evidence("얼굴 조작 의심 구간", "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                                        List.of("temporal", "face-swap"), 3.0, 7.0)),
                                "base64pngdata"), null, null),
                        null,
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
    void getAnalysisJob_withNoFaceDetectedButScamDetectionPresent_shouldReturn200WithNullAiDetection() throws Exception {
        // 얼굴없음으로 aiDetection은 null이지만 job 자체는 COMPLETED이고 scamDetection은
        // 살아있는 경우 - 실기 검증 중 500(NPE)이 발생해 재현하는 회귀 테스트(2026-09-21).
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        var scamDetection = new com.veritae.veritae_server.detection.ScamDetectionResult(
                "lilju", 0.82,
                List.of(new com.veritae.veritae_server.detection.ScamEvidence("계좌번호를 알려주세요", 0.95)));
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(
                new com.veritae.veritae_server.analysis.AnalysisJobView(
                        jobId,
                        com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.COMPLETED,
                        new VideoAnalysisResult(null, scamDetection, "NO_FACE_DETECTED"),
                        "NO_FACE_DETECTED",
                        "영상에서 얼굴을 찾을 수 없어 AI판독은 제공되지 않습니다.",
                        null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.aiDetection").doesNotExist())
                .andExpect(jsonPath("$.scamDetection.score").value(0.82))
                .andExpect(jsonPath("$.errorCode").value("NO_FACE_DETECTED"));
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

    @Test
    @WithMockUser
    void getAnalysisRecords_withAuthenticatedMember_shouldReturn200WithRecentContent() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = new ImageAnalysisResult(new ImageDetectionResult("spai", 0.1, null), null);
        var record = com.veritae.veritae_server.domain.analysisrecord.AnalysisRecord.completedSync(
                memberId, com.veritae.veritae_server.domain.analysisrecord.Modality.IMAGE,
                objectMapper.writeValueAsString(result), 0.1, null);
        when(analysisHistoryService.getRecords(memberId)).thenReturn(List.of(record));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].modality").value("IMAGE"))
                .andExpect(jsonPath("$.content[0].imageDetection.model").value("spai"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].audioDetection").doesNotExist())
                .andExpect(jsonPath("$.content[0].videoDetection").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("audioDetection"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("videoDetection"))));
    }

    @Test
    void getAnalysisRecords_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void getAnalysisReport_withAuthenticatedMember_shouldReturn200WithCounts() throws Exception {
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisHistoryService.getReport(memberId)).thenReturn(
                new com.veritae.veritae_server.analysis.AnalysisHistoryService.AnalysisReportView(23, 10, 8, 5, 3, 2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(23))
                .andExpect(jsonPath("$.scamDetectedCount").value(2));
    }

    @Test
    void getAnalysisReport_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/report"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void analyzeImage_shouldReturnRecordIdAsId() throws Exception {
        var file = new MockMultipartFile("file", "test.jpg", "image/jpeg", "fake-bytes".getBytes());
        java.util.UUID recordId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(java.util.UUID.randomUUID());
        when(imageAnalysisService.analyzeImage(any(), any())).thenReturn(new AnalysisOutcome<>(recordId,
                new ImageAnalysisResult(new ImageDetectionResult("spai", 0.87, null), null)));

        mockMvc.perform(multipart("/api/v1/analysis/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId.toString()));
    }

    @Test
    @WithMockUser
    void analyzeAudio_whenAudioTooLong_shouldReturn400WithAudioTooLongErrorCode() throws Exception {
        var file = new MockMultipartFile("file", "long.m4a", "audio/mp4", "fake-bytes".getBytes());
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(java.util.UUID.randomUUID());
        when(audioAnalysisService.analyzeAudio(any(), any()))
                .thenThrow(new com.veritae.veritae_server.detection.AudioTooLongException("음성 길이가 5분을 초과합니다."));

        mockMvc.perform(multipart("/api/v1/analysis/audio").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUDIO_TOO_LONG"))
                .andExpect(jsonPath("$.detail").value("음성 길이가 5분을 초과합니다."));
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withPendingJob_shouldReturnRetryAfterAndJobsAhead() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(new com.veritae.veritae_server.analysis.AnalysisJobView(
                jobId, com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.PENDING, null, null, null, 2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.jobsAhead").value(2));
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withProcessingJob_shouldReturnRetryAfter() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(new com.veritae.veritae_server.analysis.AnalysisJobView(
                jobId, com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.PROCESSING, null, null, null, null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.jobsAhead").doesNotExist());
    }

    @Test
    @WithMockUser
    void getAnalysisJob_withFinishedJob_shouldNotReturnRetryAfter() throws Exception {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(videoAnalysisService.getJob(jobId, memberId)).thenReturn(new com.veritae.veritae_server.analysis.AnalysisJobView(
                jobId, com.veritae.veritae_server.domain.analysisrecord.AnalysisJobStatus.FAILED, null,
                "ANALYSIS_FAILED", "영상 분석 중 오류가 발생했습니다.", null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/analysis/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @WithMockUser
    void getAnalysisRecordMedia_withoutRange_shouldReturn200WithOriginalBytesAndHeaders() throws Exception {
        java.util.UUID recordId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisMediaService.openOriginal(recordId, memberId, null)).thenReturn(new MediaObject(
                new java.io.ByteArrayInputStream("original".getBytes()), "image/jpeg", 8, null));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + recordId + "/media"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Length", "8"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().doesNotExist("Content-Range"))
                .andExpect(content().bytes("original".getBytes()));
    }

    @Test
    @WithMockUser
    void getAnalysisRecordMedia_withRange_shouldReturn206WithContentRange() throws Exception {
        java.util.UUID recordId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisMediaService.openOriginal(recordId, memberId, "bytes=0-3")).thenReturn(new MediaObject(
                new java.io.ByteArrayInputStream("orig".getBytes()), "video/mp4", 4, "bytes 0-3/8"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + recordId + "/media").header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(header().string("Content-Range", "bytes 0-3/8"))
                .andExpect(content().bytes("orig".getBytes()));
    }

    @Test
    @WithMockUser
    void getAnalysisRecordMedia_whenOriginalMissing_shouldReturn404MediaNotAvailable() throws Exception {
        java.util.UUID recordId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisMediaService.openOriginal(recordId, memberId, null))
                .thenThrow(new com.veritae.veritae_server.analysis.MediaNotAvailableException());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + recordId + "/media"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_NOT_AVAILABLE"));
    }

    @Test
    @WithMockUser
    void getAnalysisRecordMedia_whenRecordNotFound_shouldReturn404AnalysisRecordNotFound() throws Exception {
        java.util.UUID recordId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisMediaService.openOriginal(recordId, memberId, null))
                .thenThrow(new com.veritae.veritae_server.analysis.AnalysisRecordNotFoundException());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + recordId + "/media"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ANALYSIS_RECORD_NOT_FOUND"));
    }

    @Test
    @WithMockUser
    void getAnalysisRecordMedia_whenRangeNotSatisfiable_shouldReturn416() throws Exception {
        java.util.UUID recordId = java.util.UUID.randomUUID();
        java.util.UUID memberId = java.util.UUID.randomUUID();
        when(authenticatedMemberResolver.currentMemberId()).thenReturn(memberId);
        when(analysisMediaService.openOriginal(recordId, memberId, "bytes=999-"))
                .thenThrow(new com.veritae.veritae_server.analysis.RangeNotSatisfiableException());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + recordId + "/media").header("Range", "bytes=999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.errorCode").value("RANGE_NOT_SATISFIABLE"));
    }

    @Test
    void getAnalysisRecordMedia_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/analysis/records/" + java.util.UUID.randomUUID() + "/media"))
                .andExpect(status().isUnauthorized());
    }
}
