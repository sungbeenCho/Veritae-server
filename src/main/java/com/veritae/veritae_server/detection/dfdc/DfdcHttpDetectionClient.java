package com.veritae.veritae_server.detection.dfdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
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
    public VideoDetectionResult detectVideo(byte[] videoBytes, String filename, String contentType) {
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

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }

            List<Evidence> evidence = response.aiDetection().evidence().stream()
                    .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                    .toList();
            return new VideoDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    evidence,
                    response.aiDetection().evidenceImage());
        } catch (RestClientResponseException e) {
            // 422는 탐지 서버가 "얼굴을 못 찾음"을 명시적으로 알려주는 정상적인 사용자 케이스라,
            // 그 외 상태코드(장애성)와 구분해서 전용 예외로 던진다(2026-08-27).
            if (e.getStatusCode().value() == 422) {
                throw new NoFaceDetectedException();
            }
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record DfdcResponse(@JsonProperty("ai_detection") AiDetection aiDetection) {
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
