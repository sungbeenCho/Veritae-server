package com.veritae.veritae_server.config;

import com.veritae.veritae_server.detection.DetectionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 탐지 서버(veritae-detection-server) 호출용 RestClient. base-url 과 timeout 은
 * detection.* 설정(DetectionProperties)에서 가져온다.
 */
@Configuration
@RequiredArgsConstructor
public class DetectionClientConfig {

    private final DetectionProperties detectionProperties;

    @Bean
    public RestClient detectionRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) detectionProperties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) detectionProperties.readTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(detectionProperties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
