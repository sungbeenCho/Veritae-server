package com.veritae.veritae_server.detection;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 탐지 서버 호출이 실패한 경우(네트워크 오류, 타임아웃, 5xx 등). 탐지 서버는 우리가
 * 직접 운영하는 별도 인프라(3060Ti 데스크탑)이므로 클라이언트 잘못이 아니라 상류 서비스
 * 장애로 보고 502 로 매핑한다.
 */
public class DetectionServiceException extends DomainException {

    public DetectionServiceException(String message, Throwable cause) {
        super("DETECTION_SERVICE_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Detection Service Unavailable", message, cause);
    }
}
