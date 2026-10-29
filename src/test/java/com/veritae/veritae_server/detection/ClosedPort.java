package com.veritae.veritae_server.detection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/** 탐지 서버가 꺼진 상황을 흉내 낸다 - 방금 비운 포트로 보내면 실제로 연결이 거부된다. */
public final class ClosedPort {

    private ClosedPort() {
    }

    public static String baseUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
