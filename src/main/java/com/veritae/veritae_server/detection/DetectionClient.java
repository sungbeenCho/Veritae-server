package com.veritae.veritae_server.detection;

/**
 * AI 생성물 탐지 + 사기 위험도 판독 요청을 한 번에 처리하는 포트. 지금은 SPAI를
 * 셀프호스팅하는 {@link com.veritae.veritae_server.detection.spai.SpaiHttpDetectionClient}가
 * 유일한 구현체지만, 포맷별로 유료 API 기반 구현체로 교체할 수 있도록 호출부는 이
 * 인터페이스만 알면 되게 분리한다.
 */
public interface DetectionClient {

    ImageAnalysisResult detectImage(byte[] imageBytes, String filename, String contentType);
}
