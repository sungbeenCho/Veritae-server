package com.veritae.veritae_server.detection.antideepfake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioDetectionClient;
import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.Evidence;
import com.veritae.veritae_server.detection.ScamDetectionJson;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * {@link AudioDetectionClient} 의 AntiDeepfake(셀프호스팅) 구현체. 탐지 서버(veritae-detection-server,
 * 이미지(SPAI)와 같은 3060Ti 데스크탑)의 POST /process/audio 를 호출한다. 이미지용
 * {@code detectionRestClient} 빈(같은 base URL)을 그대로 재사용한다 - path만 다르다.
 * 음성 판독(AntiDeepfake)과 사기감지(Lilju)를 데스크탑이 내부적으로 병렬 실행해 한
 * 응답에 ai_detection/scam_detection을 같이 담아 보내므로, HTTP 호출은 지금처럼 한 번만
 * 한다(2026-09-13).
 */
@Component
@RequiredArgsConstructor
public class AntiDeepfakeHttpDetectionClient implements AudioDetectionClient {

    private final RestClient detectionRestClient;

    @Override
    public AudioAnalysisResult detectAudio(byte[] audioBytes, String filename, String contentType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, MediaType.parseMediaType(contentType));

        try {
            AntiDeepfakeResponse response = detectionRestClient.post()
                    .uri("/process/audio")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(builder.build())
                    .retrieve()
                    .body(AntiDeepfakeResponse.class);

            if (response == null || response.aiDetection() == null) {
                throw new DetectionServiceException("탐지 서버 응답이 비어 있습니다.", null);
            }

            List<Evidence> evidence = response.aiDetection().evidence().stream()
                    .map(e -> new Evidence(e.title(), e.description(), e.tags(), e.startSec(), e.endSec()))
                    .toList();
            var aiDetection = new AudioDetectionResult(response.aiDetection().model(), response.aiDetection().score(), evidence);
            return new AudioAnalysisResult(aiDetection, ScamDetectionJson.toScamDetection(response.scamDetection()));
        } catch (RestClientException e) {
            throw new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private record AntiDeepfakeResponse(
            @JsonProperty("ai_detection") AiDetection aiDetection,
            @JsonProperty("scam_detection") ScamDetectionJson.Dto scamDetection) {
    }

    private record AiDetection(String model, double score, List<EvidenceDto> evidence) {
    }

    private record EvidenceDto(
            String title,
            String description,
            List<String> tags,
            @JsonProperty("start_sec") double startSec,
            @JsonProperty("end_sec") double endSec) {
    }
}
