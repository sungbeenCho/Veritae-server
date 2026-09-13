package com.veritae.veritae_server.detection.spai;

import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.ImageAnalysisResult;
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
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.aiDetection().model()).isEqualTo("spai");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
        assertThat(result.scamDetection()).isNull();
        server.verify();
    }

    @Test
    void detectImage_withEvidenceImageInResponse_shouldParseEvidenceImage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87, "
                                + "\"evidence_image\": \"base64data\"}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.aiDetection().evidenceImage()).isEqualTo("base64data");
    }

    @Test
    void detectImage_withScamDetectionInResponse_shouldParseScamDetection() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}, "
                                + "\"scam_detection\": {\"model\": \"lilju\", \"score\": 0.82, "
                                + "\"evidence\": [{\"sentence\": \"계좌번호를 알려주세요\", \"score\": 0.95}]}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.scamDetection().model()).isEqualTo("lilju");
        assertThat(result.scamDetection().score()).isEqualTo(0.82);
        assertThat(result.scamDetection().evidence()).hasSize(1);
        assertThat(result.scamDetection().evidence().get(0).sentence()).isEqualTo("계좌번호를 알려주세요");
    }

    @Test
    void detectImage_withoutScamDetectionInResponse_shouldParseNull() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"ai_detection\": {\"model\": \"spai\", \"score\": 0.87}}",
                        MediaType.APPLICATION_JSON));
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        ImageAnalysisResult result = client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg");

        assertThat(result.scamDetection()).isNull();
    }

    @Test
    void detectImage_whenServerReturnsError_shouldThrowDetectionServiceException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andRespond(withServerError());
        SpaiHttpDetectionClient client = new SpaiHttpDetectionClient(builder.build());

        assertThatThrownBy(() -> client.detectImage("fake-bytes".getBytes(), "test.jpg", "image/jpeg"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
