package com.veritae.veritae_server.detection.antideepfake;

import com.veritae.veritae_server.detection.AudioDetectionResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AntiDeepfakeHttpDetectionClientTest {

    @Test
    void detectAudio_withSuccessResponse_shouldParseScoreAndEvidence() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "antideepfake", "score": 0.87, "evidence": [
                            {"title": "시간 구간 이상 패턴", "description": "0.5초~1.2초 구간에서 합성 흔적이 감지됨",
                             "tags": ["temporal"], "start_sec": 0.5, "end_sec": 1.2}
                        ]}}
                        """,
                        MediaType.APPLICATION_JSON));
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When
        AudioDetectionResult result = client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav");

        // Then
        assertThat(result.model()).isEqualTo("antideepfake");
        assertThat(result.score()).isEqualTo(0.87);
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).title()).isEqualTo("시간 구간 이상 패턴");
        assertThat(result.evidence().get(0).startSec()).isEqualTo(0.5);
        assertThat(result.evidence().get(0).endSec()).isEqualTo(1.2);
        server.verify();
    }

    @Test
    void detectAudio_whenServerReturnsError_shouldThrowDetectionServiceException() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andRespond(withServerError());
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
