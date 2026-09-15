package com.veritae.veritae_server.detection.spai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.DetectionClient;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
import com.veritae.veritae_server.detection.ImageDetectionResult;
import com.veritae.veritae_server.detection.ScamDetectionJson;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link DetectionClient} 의 SPAI(셀프호스팅) 구현체. 탐지 서버(veritae-detection-server,
 * 3060Ti 데스크탑)의 POST /process/image 를 호출한다. 이미지 판독(SPAI)과 사기감지
 * (Lilju)를 데스크탑이 내부적으로 병렬 실행해 한 응답에 ai_detection/scam_detection을
 * 같이 담아 보내므로, HTTP 호출은 지금처럼 한 번만 한다(2026-09-13).
 */
@Component
@RequiredArgsConstructor
public class SpaiHttpDetectionClient implements DetectionClient {

    private final RestClient detectionRestClient;

    @Override
    public ImageAnalysisResult detectImage(byte[] imageBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            SpaiResponse response = detectionRestClient.post()
                    .uri("/process/image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(SpaiResponse.class);

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }
            var aiDetection = new ImageDetectionResult(
                    response.aiDetection().model(),
                    response.aiDetection().score(),
                    response.aiDetection().evidenceImage());
            return new ImageAnalysisResult(aiDetection, ScamDetectionJson.toScamDetection(response.scamDetection()));
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record SpaiResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection) {
    }

    private record AiDetection(
            String model,
            double score,
            @JsonProperty("evidence_image") String evidenceImage) {
    }
}
