package com.veritae.veritae_server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJob;
import com.veritae.veritae_server.domain.analysisjob.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * VideoAnalysisService.submitVideo() 가 job을 만든 뒤 이 빈의 process()를 호출해
 * 실제 탐지 서버 호출 + 상태 갱신을 비동기로 수행한다. 별도 빈으로 분리한 이유는
 * VideoAnalysisService의 클래스 주석 참고(스프링 @Async self-invocation 함정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoAnalysisAsyncWorker {

    // resultJson은 우리가 직접 쓰고 직접 읽는 순수 내부 저장 포맷이지 iOS로 나가는 응답이 아니다
    // (그건 openapi-generator가 만든 Jackson 3 기반 DTO를 통해 나간다). 이 프로젝트는 Spring Boot 4 +
    // Jackson 3(tools.jackson) 기본 스택이라 com.fasterxml.jackson.databind.ObjectMapper 빈이
    // 자동 등록되지 않으므로, DI 대신 기본 설정 인스턴스를 직접 만들어 쓴다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VideoDetectionClient videoDetectionClient;
    private final AnalysisJobRepository analysisJobRepository;

    @Async("videoAnalysisExecutor")
    public void process(UUID jobId, byte[] videoBytes, String filename, String contentType) {
        AnalysisJob job = analysisJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("방금 생성한 job을 찾을 수 없습니다: " + jobId));
        job.markProcessing();
        analysisJobRepository.save(job);

        try {
            VideoDetectionResult result = videoDetectionClient.detectVideo(videoBytes, filename, contentType);
            job.markCompleted(writeResultJson(result));
        } catch (NoFaceDetectedException e) {
            // 얼굴 없음은 진짜 장애가 아니라 정상적인 사용자 케이스라 error가 아니라 info로 남기고,
            // 사용자에게도 원인을 구체적으로 알려준다(2026-08-27 - 예전엔 이것도 아래 catch(Exception)에
            // 묶여서 "영상 분석 중 오류가 발생했습니다"로만 나왔었음).
            log.info("영상 분석: 얼굴 미검출 jobId={}", jobId);
            job.markFailed("영상에서 얼굴을 찾을 수 없습니다. 얼굴이 잘 보이는 영상으로 다시 시도해주세요.");
        } catch (Exception e) {
            // 탐지 서버(Python)가 던지는 원문 에러 메시지에는 내부 경로/traceback 일부가 섞여 나올 수 있어
            // 클라이언트(errorMessage)에 그대로 노출하지 않는다. 상세 원인은 로그에만 남기고,
            // 사용자에게는 일반화된 메시지만 전달한다.
            log.error("영상 분석 실패 jobId={}", jobId, e);
            job.markFailed("영상 분석 중 오류가 발생했습니다.");
        }
        analysisJobRepository.save(job);
    }

    private String writeResultJson(VideoDetectionResult result) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
