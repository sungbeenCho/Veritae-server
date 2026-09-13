package com.veritae.veritae_server.detection.dfdc;

import com.veritae.veritae_server.detection.VideoAnalysisResult;
import com.veritae.veritae_server.detection.DetectionServiceException;
import com.veritae.veritae_server.detection.NoFaceDetectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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
        VideoAnalysisResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.aiDetection().model()).isEqualTo("dfdc");
        assertThat(result.aiDetection().score()).isEqualTo(0.91);
        assertThat(result.aiDetection().evidenceImage()).isEqualTo("base64pngdata");
        assertThat(result.aiDetection().evidence()).hasSize(1);
        assertThat(result.aiDetection().evidence().get(0).title()).isEqualTo("얼굴 조작 의심 구간");
        assertThat(result.aiDetection().evidence().get(0).startSec()).isEqualTo(3.0);
        assertThat(result.scamDetection()).isNull();
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
        VideoAnalysisResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.aiDetection().evidenceImage()).isNull();
        assertThat(result.aiDetection().evidence()).isEmpty();
    }

    @Test
    void detectVideo_withScamDetectionInResponse_shouldParseScamDetection() {
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
                        ]}, "scam_detection": {"model": "lilju", "score": 0.82,
                            "evidence": [{"sentence": "계좌번호를 알려주세요", "score": 0.95}]}}
                        """,
                        MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When
        VideoAnalysisResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.scamDetection().model()).isEqualTo("lilju");
        assertThat(result.scamDetection().score()).isEqualTo(0.82);
        assertThat(result.scamDetection().evidence()).hasSize(1);
        assertThat(result.scamDetection().evidence().get(0).sentence()).isEqualTo("계좌번호를 알려주세요");
    }

    @Test
    void detectVideo_withoutScamDetectionInResponse_shouldParseNull() {
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
        VideoAnalysisResult result = client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4");

        // Then
        assertThat(result.scamDetection()).isNull();
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

    @Test
    void detectVideo_whenServerReturns422_shouldThrowNoFaceDetectedException() {
        // Given: 탐지 서버가 얼굴 미검출을 422로 알려주는 경우 - 이건 장애가 아니라 정상적인
        // 사용자 케이스라 DetectionServiceException(502류)과 구분해야 한다(2026-08-27).
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/video"))
                .andRespond(withStatus((HttpStatusCode) HttpStatus.UNPROCESSABLE_CONTENT)
                        .body("{\"detail\": \"얼굴을 찾을 수 없습니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        DfdcHttpDetectionClient client = new DfdcHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectVideo("fake-bytes".getBytes(), "test.mp4", "video/mp4"))
                .isInstanceOf(NoFaceDetectedException.class);
    }
}
