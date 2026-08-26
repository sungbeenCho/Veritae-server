package com.veritae.veritae_server.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * detection.* 설정. serviceUrl 은 탐지 서버(3060Ti 데스크탑에서 상시 구동되는
 * veritae-detection-server)의 LAN 주소다. 고정 IP가 아니면 데스크탑 재부팅 시 바뀔 수 있다.
 * videoReadTimeout 은 이미지/음성과 별도 - 영상은 비동기라 사용자를 기다리게 하지 않으므로
 * 훨씬 넉넉하게 잡는다(초기 추정치, 실측 후 조정 필요 - 오디오 때도 같은 이유로 조정했었음).
 */
@ConfigurationProperties(prefix = "detection")
public record DetectionProperties(String serviceUrl, Duration connectTimeout, Duration readTimeout,
                                   Duration videoReadTimeout) {
}
