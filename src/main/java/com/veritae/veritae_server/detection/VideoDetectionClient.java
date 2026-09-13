package com.veritae.veritae_server.detection;

/**
 * 영상 얼굴조작(face-swap) 딥페이크 탐지 요청을 추상화하는 포트. 이미지/음성과 별도
 * 인터페이스로 분리한 이유는 docs/superpowers/specs/2026-08-18-audio-ai-detection-design.md §4
 * 참고(영상이 비동기 패턴을 갖게 되어 하나의 인터페이스에 다 몰아넣는 게 부담이 됨 - 실제로
 * 이번에 그 비동기화가 일어났다).
 */
public interface VideoDetectionClient {

    VideoAnalysisResult detectVideo(byte[] videoBytes, String filename, String contentType);
}
