package com.veritae.veritae_server.detection;

/**
 * 영상에서 얼굴을 하나도 못 찾은 경우(탐지 서버가 422로 응답) 전용 예외.
 * {@link com.veritae.veritae_server.common.exception.DomainException}이 아니라 일반
 * {@link RuntimeException}인 이유: 영상 분석은
 * 비동기라 이 예외가 HTTP 응답으로 직접 나가는 일이 없다({@code VideoAnalysisAsyncWorker}
 * 안에서 잡혀서 {@code AnalysisJob}의 errorMessage로 변환됨) - DomainException이 하는 일
 * (HTTP 상태코드 매핑)이 여기선 쓰일 일이 없어 그 기반 클래스를 쓸 이유가 없다.
 */
public class NoFaceDetectedException extends RuntimeException {

    public NoFaceDetectedException() {
        super("영상에서 얼굴을 찾을 수 없습니다.");
    }
}
