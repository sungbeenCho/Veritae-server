package com.veritae.veritae_server.detection;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 탐지 서버에 연결 자체가 안 되는 경우(데스크탑이 꺼짐, 서버 프로세스 종료, Tailscale 끊김 등). 탐지 서버는
 * 필요할 때만 켜는 운영 방식이라(2026-10-28) 처리 중 오류(502)와 구분해 503 으로 알려, 앱이 "잠시 후 다시"를
 * 안내할 수 있게 한다.
 */
public class DetectionServerOfflineException extends DomainException {

    public static final String USER_MESSAGE = "분석 서버가 꺼져 있어 지금은 분석할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    public DetectionServerOfflineException(Throwable cause) {
        super("DETECTION_SERVER_OFFLINE", HttpStatus.SERVICE_UNAVAILABLE, "Detection Server Offline", USER_MESSAGE, cause);
    }
}
