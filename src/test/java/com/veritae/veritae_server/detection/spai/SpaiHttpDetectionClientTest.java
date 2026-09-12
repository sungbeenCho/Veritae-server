package com.veritae.veritae_server.detection.spai;

import com.veritae.veritae_server.detection.ImageDetectionResult;
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

class SpaiHttpDetectionClientTest {

    @Test
    void detectImage_withSuccessResponse_shouldParseScore() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        // When
        ImageDetectionResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        // Then
        assertThat(result.model()).isEqualTo("spai");
        assertThat(result.score()).isEqualTo(0.87);
        server.verify();
    }

    @Test
    void detectImage_withEvidenceImageInResponse_shouldParseEvidenceImage() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87, "
                                + "\"evidence_image\": \"base64data\"}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        // When
        ImageDetectionResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        // Then
        assertThat(result.evidenceImage()).isEqualTo("base64data");
    }

    @Test
    void detectImage_withoutEvidenceImageInResponse_shouldParseNull() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        // When
        ImageDetectionResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        // Then
        assertThat(result.evidenceImage()).isNull();
    }

    @Test
    void detectImage_whenServerReturnsError_shouldThrowDetectionServiceException() {
        // Given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andRespond(withServerError());
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
