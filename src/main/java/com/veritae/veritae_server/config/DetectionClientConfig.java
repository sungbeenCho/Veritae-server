package com.veritae.veritae_server.config;

import com.veritae.veritae_server.detection.DetectionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 탐지 서버(veritae-detection-server) 호출용 RestClient. base-url 과 timeout 은
 * detection.* 설정(DetectionProperties)에서 가져온다. videoDetectionRestClient 는
 * detectionRestClient 와 base URL(같은 탐지 서버)은 같지만 read-timeout 만 훨씬 길다 -
 * 영상은 비동기 처리라 이미지/음성의 sync 타임아웃 예산에 영향을 주면 안 되기 때문에
 * 별도 빈으로 분리했다.
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

    @Bean
    public RestClient videoDetectionRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) detectionProperties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) detectionProperties.videoReadTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(detectionProperties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
