package com.veritae.veritae_server.detection;

import com.veritae.veritae_server.common.exception.DomainException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 탐지 서버 호출 실패를 "서버가 꺼짐(연결 불가)"과 "처리 중 오류"로 나눈다. 연결 단계에서 실패했을 때만 꺼짐으로 본다 -
 * 연결된 뒤 응답이 늦은 읽기 시간 초과는 분석이 오래 걸린 것이라 처리 오류로 둔다.
 */
public final class DetectionCallFailures {

    private DetectionCallFailures() {
    }

    public static DomainException translate(RestClientException e) {
        if (e instanceof ResourceAccessException && isConnectionFailure(e.getCause())) {
            return new DetectionServerOfflineException(e);
        }
        return new DetectionServiceException("탐지 서버 호출에 실패했습니다: " + e.getMessage(), e);
    }

    private static boolean isConnectionFailure(Throwable cause) {
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException) {
            return true;
        }
        // HttpURLConnection 은 연결 시간 초과와 읽기 시간 초과를 같은 예외 타입으로 던지고 메시지로만 구분한다
        // ("Connect timed out" / "Read timed out"). 꺼진 데스크탑(Tailscale 상대 없음)은 연결 시간 초과로 끝난다.
        return cause instanceof SocketTimeoutException
                && cause.getMessage() != null
                && cause.getMessage().toLowerCase().contains("connect");
    }
}
