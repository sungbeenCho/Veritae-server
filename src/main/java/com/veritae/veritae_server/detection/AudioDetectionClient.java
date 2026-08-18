package com.veritae.veritae_server.detection;

/**
 * 음성 AI 생성물 탐지 요청을 추상화하는 포트. 이미지의 {@link DetectionClient}와 별도
 * 인터페이스로 분리한 이유는 docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §4 참고
 * (영상이 추후 비동기 패턴을 갖게 되면 하나의 인터페이스에 다 몰아넣는 게 부담이 됨).
 */
public interface AudioDetectionClient {

    AiDetectionResult detectAudio(byte[] audioBytes, String filename, String contentType);
}
