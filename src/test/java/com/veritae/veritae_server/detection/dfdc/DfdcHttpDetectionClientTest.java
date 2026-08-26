package com.veritae.veritae_server.detection.dfdc;

import com.veritae.veritae_server.detection.AiDetectionResult;
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

class DfdcHttpDetectionClientTest {

    @Test
    void detectVideo_withSuccessResponse_shouldParseScoreEvidenceAndEvidenceImage() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "dfdc", "score": 0.91, "evidence_image": "base64pngdata", "evidence": [
                            {"title": "얼굴 조작 의심 구간", "description": "3.0초~7.0초 구간에서 얼굴 합성 흔적이 감지됨",
                             "tags": ["temporal", "face-swap"], "start_sec": 3.0, "end_sec": 7.0}
                        ]}}
                        """,
                        MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When
        AiDetectionResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.model()).isEqualTo("dfdc");
        assertThat(result.score()).isEqualTo(0.91);
        assertThat(result.evidenceImage()).isEqualTo("base64pngdata");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).title()).isEqualTo("얼굴 조작 의심 구간");
        assertThat(result.evidence().get(0).startSec()).isEqualTo(3.0);
        server.verify();
    }

    @Test
    void detectVideo_withNullEvidenceImage_shouldReturnNullEvidenceImage() {
        // Given: 히트맵 생성이 실패한 경우(Grad-CAM best-effort 실패) evidence_image가 null로 옴
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andRespond(withSuccess(
                        """
                        {"ai_detection": {"model": "dfdc", "score": 0.12, "evidence_image": null, "evidence": []}}
                        """,
                        MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When
        AiDetectionResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.evidenceImage()).isNull();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void detectVideo_whenServerReturnsError_shouldThrowDetectionServiceException() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andRespond(withServerError());
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
