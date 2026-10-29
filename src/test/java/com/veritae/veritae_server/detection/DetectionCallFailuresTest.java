package com.veritae.veritae_server.detection;

import com.veritae.veritae_server.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionCallFailuresTest {

    @Test
    void translate_whenConnectionRefused_shouldBeServerOffline() {
        DomainException result = DetectionCallFailures.translate(
                new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        assertThat(result).isInstanceOf(DetectionServerOfflineException.class);
        assertThat(result.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getErrorCode()).isEqualTo("DETECTION_SERVER_OFFLINE");
    }

    @Test
    void translate_whenConnectTimedOut_shouldBeServerOffline() {
        // 데스크탑이 꺼져 Tailscale 상대가 없으면 연결 거부가 아니라 연결 시간 초과로 끝난다.
        DomainException result = DetectionCallFailures.translate(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Connect timed out")));

        assertThat(result).isInstanceOf(DetectionServerOfflineException.class);
    }

    @Test
    void translate_whenNoRouteOrUnknownHost_shouldBeServerOffline() {
        assertThat(DetectionCallFailures.translate(
                new ResourceAccessException("I/O error", new NoRouteToHostException("No route to host"))))
                .isInstanceOf(DetectionServerOfflineException.class);
        assertThat(DetectionCallFailures.translate(
                new ResourceAccessException("I/O error", new UnknownHostException("desktop"))))
                .isInstanceOf(DetectionServerOfflineException.class);
    }

    @Test
    void translate_whenReadTimedOut_shouldStayDetectionServiceFailure() {
        // 연결은 됐고 분석이 오래 걸린 경우다 - 서버가 꺼진 게 아니다.
        DomainException result = DetectionCallFailures.translate(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThat(result).isInstanceOf(DetectionServiceException.class);
        assertThat(result.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void translate_whenServerReturnsError_shouldStayDetectionServiceFailure() {
        DomainException result = DetectionCallFailures.translate(
                HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "error", null, null, null));

        assertThat(result).isInstanceOf(DetectionServiceException.class);
    }
}
