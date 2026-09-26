package com.veritae.veritae_server.detection.antideepfake;

import com.veritae.veritae_server.detection.AudioAnalysisResult;
import com.veritae.veritae_server.detection.AudioTooLongException;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
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
        AudioAnalysisResult result = client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav");

        // Then
        assertThat(result.aiDetection().model()).isEqualTo("antideepfake");
        assertThat(result.aiDetection().score()).isEqualTo(0.87);
        assertThat(result.aiDetection().evidence()).hasSize(1);
        assertThat(result.aiDetection().evidence().get(0).title()).isEqualTo("시간 구간 이상 패턴");
        assertThat(result.aiDetection().evidence().get(0).startSec()).isEqualTo(0.5);
        assertThat(result.aiDetection().evidence().get(0).endSec()).isEqualTo(1.2);
        assertThat(result.scamDetection()).isNull();
        server.verify();
    }

    @Test
    void detectAudio_withScamDetectionInResponse_shouldParseScamDetection() {
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
                        ]}, "scam_detection": {"model": "lilju", "score": 0.82,
                            "evidence": [{"sentence": "계좌번호를 알려주세요", "score": 0.95}]}}
                        """,
                        MediaType.APPLICATION_JSON));
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When
        AudioAnalysisResult result = client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav");

        // Then
        assertThat(result.scamDetection().model()).isEqualTo("lilju");
        assertThat(result.scamDetection().score()).isEqualTo(0.82);
        assertThat(result.scamDetection().evidence()).hasSize(1);
        assertThat(result.scamDetection().evidence().get(0).sentence()).isEqualTo("계좌번호를 알려주세요");
    }

    @Test
    void detectAudio_withoutScamDetectionInResponse_shouldParseNull() {
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
        AudioAnalysisResult result = client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav");

        // Then
        assertThat(result.scamDetection()).isNull();
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

    @Test
    void detectAudio_whenServerRejectsTooLongAudio_shouldThrowAudioTooLongException() {
        // Given: 탐지 서버는 5분 초과 음성을 모델 실행 전에 400 + detail.code=AUDIO_TOO_LONG 으로 거부한다.
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"detail": {"code": "AUDIO_TOO_LONG", "message": "음성 길이가 5분을 초과합니다."}}
                              """));
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav"))
                .isInstanceOf(AudioTooLongException.class)
                .hasMessage("음성 길이가 5분을 초과합니다.");
    }

    @Test
    void detectAudio_whenServerReturnsOtherBadRequest_shouldThrowDetectionServiceException() {
        // Given: AUDIO_TOO_LONG 이 아닌 400(예: 형식 오류 문자열 detail)은 기존처럼 탐지 서버 오류로 본다.
        RestClient.Builder builder = RestClient.builder().baseUrl("http://desktop:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/audio"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"empty file\"}"));
        AntiDeepfakeHttpDetectionClient client = new AntiDeepfakeHttpDetectionClient(builder.build());

        // When / Then
        assertThatThrownBy(() -> client.detectAudio("fake-bytes".getBytes(), "test.wav", "audio/wav"))
                .isInstanceOf(DetectionServiceException.class);
    }
}
