package com.veritae.veritae_server.config;

import com.veritae.veritae_server.detection.DetectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DetectionClientConfigTest {

    private static DetectionClientConfig config(String apiKey) {
        return new DetectionClientConfig(new DetectionProperties(
                "http://desktop:8000", Duration.ofSeconds(5), Duration.ofSeconds(300), Duration.ofMinutes(20), apiKey));
    }

    @Test
    void requestsToDetectionServer_shouldCarryApiKeyHeader() {
        RestClient.Builder builder = config("secret").baseBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(header("X-Detection-Key", "secret"))
                .andRespond(withSuccess());

        builder.build().post().uri("/process/image").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void blankApiKey_shouldNotSendHeader() {
        RestClient.Builder builder = config("").baseBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://desktop:8000/process/image"))
                .andExpect(headerDoesNotExist("X-Detection-Key"))
                .andRespond(withSuccess());

        builder.build().post().uri("/process/image").retrieve().toBodilessEntity();

        server.verify();
    }
}
