package com.veritae.veritae_server.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.veritae.veritae_server.media.MediaObject;
import com.veritae.veritae_server.media.MediaObjectNotFoundException;
import com.veritae.veritae_server.media.MediaRangeNotSatisfiableException;
import com.veritae.veritae_server.media.R2MediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 AWS SDK S3 클라이언트(MediaStorageConfig.r2Client 와 똑같은 설정)를 가짜 HTTP 서버에 연결해, R2 로
 * 실제로 어떤 요청이 나가는지 확인한다. R2MediaStorageTest 는 S3Client 를 목으로 바꿔서 이 부분(주소 형식,
 * 체크섬/청크 헤더, Range 전달, 오류 응답 해석)을 볼 수 없다. 실제 R2 와의 최종 확인은 계정이 생긴 뒤 따로 한다.
 */
class R2MediaStorageWireTest {

    private static final String BUCKET = "veritae-media";

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Recorded, Reply> responder = r -> new Reply(200, Map.of(), new byte[0]);
    private R2MediaStorage storage;

    record Recorded(String method, String pathAndQuery, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst().orElse(null);
        }
    }

    record Reply(int status, Map<String, String> headers, byte[] body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort());
        storage = new R2MediaStorage(MediaStorageConfig.r2Client(endpoint, "test-key", "test-secret"), BUCKET);
    }

    @AfterEach
    void tearDown() {
        storage.close();
        server.stop(0);
    }

    @Test
    void put_shouldSendPathStylePutWithPlainBodyAndNoOptionalChecksumHeaders() {
        storage.put("media/m/r", "hello".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        Recorded put = requests.get(0);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.pathAndQuery()).isEqualTo("/" + BUCKET + "/media/m/r"); // path-style
        assertThat(put.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(put.body()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        // chunkedEncodingEnabled(false): aws-chunked 스트리밍 서명을 쓰지 않는다.
        assertThat(put.header("Content-Encoding")).isNull();
        assertThat(put.header("x-amz-content-sha256")).doesNotStartWith("STREAMING");
        assertThat(put.header("x-amz-decoded-content-length")).isNull();
        // requestChecksumCalculation(WHEN_REQUIRED): PutObject 에는 선택 체크섬을 붙이지 않는다.
        assertThat(put.headers().keySet()).noneMatch(h -> h.toLowerCase().startsWith("x-amz-checksum"));
        assertThat(put.header("x-amz-sdk-checksum-algorithm")).isNull();
        assertThat(put.header("x-amz-trailer")).isNull();
        assertThat(put.header("Authorization")).startsWith("AWS4-HMAC-SHA256");
    }

    @Test
    void get_withRange_shouldForwardRangeAndReturnPartialObject() throws IOException {
        responder = r -> new Reply(206, Map.of(
                "Content-Type", "video/mp4",
                "Content-Range", "bytes 0-1/5"), "he".getBytes(StandardCharsets.UTF_8));

        MediaObject object = storage.get("media/m/r", "bytes=0-1");

        Recorded get = requests.get(0);
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.pathAndQuery()).isEqualTo("/" + BUCKET + "/media/m/r");
        assertThat(get.header("Range")).isEqualTo("bytes=0-1");
        assertThat(object.partial()).isTrue();
        assertThat(object.contentRange()).isEqualTo("bytes 0-1/5");
        assertThat(object.contentType()).isEqualTo("video/mp4");
        assertThat(object.contentLength()).isEqualTo(2L);
        try (InputStream in = object.content()) {
            assertThat(in.readAllBytes()).isEqualTo("he".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void get_withoutRange_shouldReturnWholeObject() throws IOException {
        responder = r -> new Reply(200, Map.of("Content-Type", "image/png"), "hello".getBytes(StandardCharsets.UTF_8));

        MediaObject object = storage.get("media/m/r", null);

        assertThat(requests.get(0).header("Range")).isNull();
        assertThat(object.partial()).isFalse();
        assertThat(object.contentLength()).isEqualTo(5L);
        try (InputStream in = object.content()) {
            assertThat(in.readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void get_whenR2SaysNoSuchKey_shouldThrowMediaObjectNotFound() {
        responder = r -> xmlError(404, "NoSuchKey");

        assertThatThrownBy(() -> storage.get("media/m/missing", null))
                .isInstanceOf(MediaObjectNotFoundException.class);
    }

    @Test
    void get_whenR2SaysInvalidRange_shouldThrowMediaRangeNotSatisfiable() {
        responder = r -> xmlError(416, "InvalidRange");

        assertThatThrownBy(() -> storage.get("media/m/r", "bytes=999-"))
                .isInstanceOf(MediaRangeNotSatisfiableException.class);
    }

    @Test
    void deleteByPrefix_shouldListWithPrefixThenDeleteEachKey() {
        responder = r -> {
            if (r.method().equals("GET")) {
                return new Reply(200, Map.of("Content-Type", "application/xml"), ("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <Name>veritae-media</Name><Prefix>media/m/</Prefix><KeyCount>2</KeyCount>
                          <MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>
                          <Contents><Key>media/m/1</Key><Size>1</Size></Contents>
                          <Contents><Key>media/m/2</Key><Size>1</Size></Contents>
                        </ListBucketResult>
                        """).getBytes(StandardCharsets.UTF_8));
            }
            return new Reply(204, Map.of(), new byte[0]);
        };

        storage.deleteByPrefix("media/m/");

        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).pathAndQuery()).startsWith("/" + BUCKET).contains("list-type=2").contains("prefix=media%2Fm%2F");
        assertThat(requests.subList(1, requests.size()))
                .extracting(Recorded::method, Recorded::pathAndQuery)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DELETE", "/" + BUCKET + "/media/m/1"),
                        org.assertj.core.groups.Tuple.tuple("DELETE", "/" + BUCKET + "/media/m/2"));
    }

    private static Reply xmlError(int status, String code) {
        return new Reply(status, Map.of("Content-Type", "application/xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code>"
                        + "<Message>test</Message></Error>").getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String query = exchange.getRequestURI().getRawQuery();
        Recorded recorded = new Recorded(exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath() + (query != null ? "?" + query : ""),
                Map.copyOf(exchange.getRequestHeaders()), body);
        requests.add(recorded);
        Reply reply = responder.apply(recorded);
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        if (reply.body().length == 0) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            exchange.getResponseBody().write(reply.body());
        }
        exchange.close();
    }
}
