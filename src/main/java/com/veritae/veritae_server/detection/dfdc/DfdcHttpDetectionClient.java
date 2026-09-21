package com.veritae.veritae_server.detection.dfdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ScamDetectionJson;
import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.VideoDetectionClient;
import com.veritae.veritae_server.detection.VideoDetectionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * {@link VideoDetectionClient} 의 selimsef/dfdc_deepfake_challenge(셀프호스팅) 구현체.
 * 탐지 서버(veritae-detection-server, 3060Ti 데스크탑)의 POST /process/video 를 호출한다.
 * videoDetectionRestClient 빈(전용 긴 타임아웃)을 쓴다 - DetectionClientConfig 참고.
 * 같은 타입(RestClient) 빈이 2개(detectionRestClient, videoDetectionRestClient) 있어
 * @RequiredArgsConstructor 대신 @Qualifier가 붙은 생성자를 명시적으로 쓴다(같이 쓰면
 * Lombok이 똑같은 시그니처의 생성자를 하나 더 만들어 중복 생성자 컴파일 에러가 난다).
 */
@Component
public class DfdcHttpDetectionClient implements VideoDetectionClient {

    private final RestClient videoDetectionRestClient;

    public DfdcHttpDetectionClient(@Qualifier("videoDetectionRestClient") RestClient videoDetectionRestClient) {
        this.videoDetectionRestClient = videoDetectionRestClient;
    }

    @Override
    public VideoAnalysisResult detectVideo(byte[] videoBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(videoBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            DfdcResponse response = videoDetectionRestClient.post()
                    .uri("/process/video")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(DfdcResponse.class);

            if (response == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }

            // aiDetection은 얼굴을 못 찾으면 null일 수 있다(정상적인 한계) - 그 경우
            // response.errorCode()에 "NO_FACE_DETECTED"가 담겨 온다(2026-09-21).
            VideoDetectionResult aiDetection = null;
            if (response.aiDetection() != null) {
                List<Evidence> evidence = response.aiDetection().evidence().stream()
                        .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                        .toList();
                aiDetection = new VideoDetectionResult(
                        response.aiDetection().model(),
                        response.aiDetection().score(),
                        evidence,
                        response.aiDetection().evidenceImage());
            }
            return new VideoAnalysisResult(
                    aiDetection, ScamDetectionJson.toScamDetection(response.scamDetection()), response.errorCode());
        } catch (RestClientResponseException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record DfdcResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection,
            @JsonProperty("error_code") String errorCode) {
    }

    private record AiDetection(
            String model,
            double score,
            List<EvidenceDto> evidence,
            @JsonProperty("evidence_image") String evidenceImage) {
    }

    private record EvidenceDto(
            String title,
            String description,
            List<String> tags,
            @JsonProperty("start_sec") double startSec,
            @JsonProperty("end_sec") double endSec) {
    }
}
