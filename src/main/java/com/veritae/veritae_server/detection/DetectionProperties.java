package com.veritae.veritae_server.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * detection.* 설정. serviceUrl 은 탐지 서버(3060Ti 데스크탑에서 상시 구동되는
 * veritae-detection-server)의 LAN 주소다. 고정 IP가 아니면 데스크탑 재부팅 시 바뀔 수 있다.
 */
@ConfigurationProperties(prefix = "detection")
public record DetectionProperties(String serviceUrl, Duration connectTimeout, Duration readTimeout) {
}
