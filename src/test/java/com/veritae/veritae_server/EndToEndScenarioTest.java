package com.veritae.veritae_server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.veritae.veritae_server.media.InMemoryMediaStorage;
import com.veritae.veritae_server.media.MediaObject;
import com.veritae.veritae_server.media.MediaRangeNotSatisfiableException;
import com.veritae.veritae_server.media.MediaStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제로 서버를 띄워 HTTP 요청으로 iOS 앱이 부르는 순서 그대로 확인하는 통합 테스트.
 * DB는 테스트용 메모리 DB(H2), 탐지 서버는 이 테스트가 띄우는 가짜 HTTP 서버, 원본 저장소(R2)는
 * 메모리 저장소로 바꾼다 - 로컬 MySQL/실제 탐지 서버/실제 R2 는 건드리지 않는다.
 * 보안 필터, 컨트롤러, 예외 처리, 스트리밍 응답(Range/206), 비동기 영상 처리가 실제로 맞물려 도는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class EndToEndScenarioTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer DETECTION_STUB = startDetectionStub();

    @LocalServerPort
    private int port;

    @Autowired
    private RangeInMemoryMediaStorage storage;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void detectionUrl(DynamicPropertyRegistry registry) {
        registry.add("detection.service-url", () -> "http://localhost:" + DETECTION_STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        DETECTION_STUB.stop(0);
    }

    @TestConfiguration
    static class StorageConfig {
        @Bean
        @Primary
        RangeInMemoryMediaStorage testMediaStorage() {
            return new RangeInMemoryMediaStorage();
        }
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    void image_analysisThenListThenDownloadWholeAndPartialOriginal() throws Exception {
        String token = signupAndLogin();
        byte[] original = "IMAGE-BYTES-0123456789".getBytes(StandardCharsets.UTF_8); // 22바이트

        HttpResponse<String> analyzed = upload("/api/v1/analysis/image", token, "photo.jpg", "image/jpeg", original);
        assertThat(analyzed.statusCode()).isEqualTo(200);
        String recordId = json(analyzed).path("id").asText();
        assertThat(recordId).isNotBlank();

        JsonNode first = json(get("/api/v1/analysis/records", token)).path("content").get(0);
        assertThat(first.path("id").asText()).isEqualTo(recordId);
        assertThat(first.path("mediaAvailable").asBoolean()).isTrue();

        HttpResponse<byte[]> whole = getBytes("/api/v1/analysis/records/" + recordId + "/media", token, null);
        assertThat(whole.statusCode()).isEqualTo(200);
        assertThat(whole.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(whole.headers().firstValue("Content-Length")).hasValue("22");
        assertThat(whole.headers().firstValue("Accept-Ranges")).hasValue("bytes");
        assertThat(whole.body()).isEqualTo(original);

        HttpResponse<byte[]> part = getBytes("/api/v1/analysis/records/" + recordId + "/media", token, "bytes=0-4");
        assertThat(part.statusCode()).isEqualTo(206);
        assertThat(part.headers().firstValue("Content-Range")).hasValue("bytes 0-4/22");
        assertThat(part.headers().firstValue("Content-Length")).hasValue("5");
        assertThat(part.body()).isEqualTo(Arrays.copyOfRange(original, 0, 5));

        HttpResponse<byte[]> outOfRange = getBytes("/api/v1/analysis/records/" + recordId + "/media", token, "bytes=999-");
        assertThat(outOfRange.statusCode()).isEqualTo(416);
        assertThat(errorCode(outOfRange)).isEqualTo("RANGE_NOT_SATISFIABLE");
    }

    @Test
    void media_ofAnotherMembersRecord_shouldBe404WithoutRevealingIt() throws Exception {
        String owner = signupAndLogin();
        String other = signupAndLogin();
        String recordId = json(upload("/api/v1/analysis/image", owner, "a.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .path("id").asText();

        HttpResponse<byte[]> response = getBytes("/api/v1/analysis/records/" + recordId + "/media", other, null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("ANALYSIS_RECORD_NOT_FOUND");
    }

    @Test
    void media_withoutToken_shouldBe401() throws Exception {
        HttpResponse<byte[]> response = getBytes("/api/v1/analysis/records/" + UUID.randomUUID() + "/media", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void retention_eleventhOriginalIsDeletedButRecordIsKept() throws Exception {
        String token = signupAndLogin();
        String memberId = json(get("/api/v1/members/me", token)).path("id").asText();
        String oldestId = null;
        for (int i = 0; i < 11; i++) {
            String id = json(upload("/api/v1/analysis/image", token, "p" + i + ".jpg", "image/jpeg", new byte[]{(byte) i}))
                    .path("id").asText();
            if (i == 0) {
                oldestId = id;
            }
            Thread.sleep(5); // 생성 시각 순서를 분명히 한다
        }

        assertThat(storage.objects().keySet().stream().filter(k -> k.startsWith("media/" + memberId + "/")))
                .hasSize(10)
                .doesNotContain("media/" + memberId + "/" + oldestId);
        HttpResponse<byte[]> oldest = getBytes("/api/v1/analysis/records/" + oldestId + "/media", token, null);
        assertThat(oldest.statusCode()).isEqualTo(404);
        assertThat(errorCode(oldest)).isEqualTo("MEDIA_NOT_AVAILABLE");
        // 분석 기록은 남아 리포트 누적 통계에 그대로 잡힌다.
        assertThat(json(get("/api/v1/analysis/report", token)).path("totalCount").asLong()).isEqualTo(11);
    }

    @Test
    void audio_overFiveMinutesIsRejectedAndNormalAudioReturnsId() throws Exception {
        String token = signupAndLogin();

        HttpResponse<String> tooLong = upload("/api/v1/analysis/audio", token, "long.m4a", "audio/mp4", new byte[]{1});
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json(tooLong).path("errorCode").asText()).isEqualTo("AUDIO_TOO_LONG");
        assertThat(json(tooLong).path("detail").asText()).isEqualTo("음성 길이가 5분을 초과합니다.");

        HttpResponse<String> ok = upload("/api/v1/analysis/audio", token, "call.m4a", "audio/mp4", new byte[]{1, 2});
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json(ok).path("id").asText()).isNotBlank();
        // 5분 초과로 거부된 음성은 기록이 남지 않는다.
        assertThat(json(get("/api/v1/analysis/records", token)).path("content")).hasSize(1);
    }

    @Test
    void video_pollingShowsRetryAfterThenCompletesWithOriginalAndErrorMessage() throws Exception {
        String token = signupAndLogin();
        byte[] video = "VIDEO-BYTES".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> accepted = upload("/api/v1/analysis/video", token, "clip.mp4", "video/mp4", video);
        assertThat(accepted.statusCode()).isEqualTo(202);
        String jobId = json(accepted).path("jobId").asText();

        HttpResponse<String> inProgress = get("/api/v1/analysis/jobs/" + jobId, token);
        assertThat(json(inProgress).path("status").asText()).isIn("PENDING", "PROCESSING");
        assertThat(inProgress.headers().firstValue("Retry-After")).hasValue("5");

        HttpResponse<String> done = pollUntilFinished(jobId, token);
        JsonNode job = json(done);
        assertThat(job.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.headers().firstValue("Retry-After")).isEmpty();
        assertThat(job.path("errorCode").asText()).isEqualTo("NO_FACE_DETECTED");
        assertThat(job.has("aiDetection")).isFalse();
        assertThat(job.path("scamDetection").path("score").asDouble()).isEqualTo(0.8);

        JsonNode item = json(get("/api/v1/analysis/records", token)).path("content").get(0);
        assertThat(item.path("id").asText()).isEqualTo(jobId);
        assertThat(item.path("errorMessage").asText()).isEqualTo(job.path("errorMessage").asText()).isNotBlank();
        assertThat(item.path("mediaAvailable").asBoolean()).isTrue();

        HttpResponse<byte[]> media = getBytes("/api/v1/analysis/records/" + jobId + "/media", token, null);
        assertThat(media.statusCode()).isEqualTo(200);
        assertThat(media.headers().firstValue("Content-Type")).hasValue("video/mp4");
        assertThat(media.body()).isEqualTo(video);
    }

    @Test
    void video_jobsAheadCountsEarlierWaitingJobs() throws Exception {
        String token = signupAndLogin();
        String[] jobs = new String[4];
        for (int i = 0; i < jobs.length; i++) {
            jobs[i] = json(upload("/api/v1/analysis/video", token, "v" + i + ".mp4", "video/mp4", new byte[]{(byte) i}))
                    .path("jobId").asText();
            Thread.sleep(5);
        }
        // 영상은 2건씩 동시에 처리된다 - 앞의 두 건이 처리 중이 되면 나머지 둘은 대기 중이다.
        waitUntil(() -> "PROCESSING".equals(status(jobs[0], token)) && "PROCESSING".equals(status(jobs[1], token)));

        JsonNode third = json(get("/api/v1/analysis/jobs/" + jobs[2], token));
        JsonNode fourth = json(get("/api/v1/analysis/jobs/" + jobs[3], token));
        assertThat(third.path("status").asText()).isEqualTo("PENDING");
        assertThat(third.path("jobsAhead").asInt()).isEqualTo(0);
        assertThat(fourth.path("status").asText()).isEqualTo("PENDING");
        assertThat(fourth.path("jobsAhead").asInt()).isEqualTo(1);

        for (String job : jobs) {
            pollUntilFinished(job, token);
        }
    }

    @Test
    void jobs_withImageRecordId_shouldBe404() throws Exception {
        String token = signupAndLogin();
        String imageId = json(upload("/api/v1/analysis/image", token, "a.jpg", "image/jpeg", new byte[]{1}))
                .path("id").asText();

        HttpResponse<String> response = get("/api/v1/analysis/jobs/" + imageId, token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("errorCode").asText()).isEqualTo("ANALYSIS_JOB_NOT_FOUND");
    }

    @Test
    void withdrawal_wrongPasswordThenRightPasswordDeletesEverythingAndBlocksOldToken() throws Exception {
        String email = "e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@veritae.app";
        String token = signupAndLogin(email);
        String memberId = json(get("/api/v1/members/me", token)).path("id").asText();
        upload("/api/v1/analysis/image", token, "a.jpg", "image/jpeg", new byte[]{1});
        assertThat(storage.objects().keySet()).anyMatch(k -> k.startsWith("media/" + memberId + "/"));

        HttpResponse<String> wrong = postJson("/api/v1/members/me/withdrawal", token, "{\"password\":\"wrong1234\"}");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(json(wrong).path("errorCode").asText()).isEqualTo("INVALID_PASSWORD");
        assertThat(get("/api/v1/members/me", token).statusCode()).isEqualTo(200);

        HttpResponse<String> right = postJson("/api/v1/members/me/withdrawal", token, "{\"password\":\"veritae123\"}");
        assertThat(right.statusCode()).isEqualTo(204);

        assertThat(storage.objects().keySet()).noneMatch(k -> k.startsWith("media/" + memberId + "/"));
        assertThat(get("/api/v1/members/me", token).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/analysis/records", token).statusCode()).isEqualTo(401);
        HttpResponse<String> relogin = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"veritae123\"}");
        assertThat(relogin.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ HTTP 도우미

    private String signupAndLogin() throws Exception {
        return signupAndLogin("e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@veritae.app");
    }

    private String signupAndLogin(String email) throws Exception {
        HttpResponse<String> signup = postJson("/api/v1/auth/signup", null,
                "{\"email\":\"" + email + "\",\"password\":\"veritae123\",\"nickname\":\"테스트\"}");
        assertThat(signup.statusCode()).isEqualTo(201);
        HttpResponse<String> login = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"veritae123\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        return json(login).path("accessToken").asText();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String token, String range) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (range != null) {
            request.header("Range", range);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> postJson(String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, String token, String filename, String contentType, byte[] bytes)
            throws Exception {
        String boundary = "e2e" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> pollUntilFinished(String jobId, String token) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/api/v1/analysis/jobs/" + jobId, token);
            String status = json(response).path("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return response;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("영상 작업이 30초 안에 끝나지 않음: " + jobId);
    }

    private String status(String jobId, String token) throws Exception {
        return json(get("/api/v1/analysis/jobs/" + jobId, token)).path("status").asText();
    }

    private static void waitUntil(ThrowingCondition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.check()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("조건이 10초 안에 만족되지 않음");
            }
            Thread.sleep(20);
        }
    }

    private interface ThrowingCondition {
        boolean check() throws Exception;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static String errorCode(HttpResponse<byte[]> response) throws IOException {
        return JSON.readTree(response.body()).path("errorCode").asText();
    }

    // ------------------------------------------------------------------ 가짜 탐지 서버 / 저장소

    /**
     * 실제 탐지 서버와 같은 응답 형식을 돌려주는 가짜 서버. 음성 파일 이름이 long 으로 시작하면 실제 탐지
     * 서버처럼 400 + AUDIO_TOO_LONG 을 준다. 영상은 처리 중 상태를 관찰할 수 있게 1.5초 뒤 얼굴없음 +
     * 사기감지 결과를 준다.
     */
    private static HttpServer startDetectionStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/process/image", exchange -> respond(exchange, 200,
                    "{\"ai_detection\":{\"model\":\"spai\",\"score\":0.9,\"evidence_image\":\"aGVhdG1hcA==\"},"
                            + "\"scam_detection\":null}"));
            server.createContext("/process/audio", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                if (body.contains("filename=\"long")) {
                    respond(exchange, 400,
                            "{\"detail\":{\"code\":\"AUDIO_TOO_LONG\",\"message\":\"음성 길이가 5분을 초과합니다.\"}}");
                } else {
                    respond(exchange, 200, "{\"ai_detection\":{\"model\":\"antideepfake\",\"score\":0.2,\"evidence\":[]},"
                            + "\"scam_detection\":null}");
                }
            });
            server.createContext("/process/video", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{\"ai_detection\":null,\"scam_detection\":{\"model\":\"lilju\",\"score\":0.8,"
                        + "\"evidence\":[{\"sentence\":\"계좌번호를 알려주세요\",\"score\":0.9}]},"
                        + "\"error_code\":\"NO_FACE_DETECTED\"}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** R2 처럼 Range("bytes=a-b", "bytes=a-")를 처리하는 메모리 저장소. */
    static class RangeInMemoryMediaStorage extends InMemoryMediaStorage implements MediaStorage {
        @Override
        public synchronized MediaObject get(String key, String range) {
            MediaObject whole = super.get(key, null);
            if (range == null) {
                return whole;
            }
            byte[] bytes;
            try (InputStream in = whole.content()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            String[] parts = range.substring("bytes=".length()).split("-", -1);
            long start = Long.parseLong(parts[0]);
            if (start >= bytes.length) {
                throw new MediaRangeNotSatisfiableException(key, range, null);
            }
            long end = parts[1].isEmpty() ? bytes.length - 1 : Math.min(Long.parseLong(parts[1]), bytes.length - 1);
            byte[] slice = Arrays.copyOfRange(bytes, (int) start, (int) end + 1);
            return new MediaObject(new ByteArrayInputStream(slice), whole.contentType(), slice.length,
                    "bytes " + start + "-" + end + "/" + bytes.length);
        }

        @Override
        public synchronized void put(String key, byte[] bytes, String contentType) {
            super.put(key, bytes, contentType);
        }

        @Override
        public synchronized void delete(String key) {
            super.delete(key);
        }

        @Override
        public synchronized void deleteByPrefix(String prefix) {
            super.deleteByPrefix(prefix);
        }
    }
}
