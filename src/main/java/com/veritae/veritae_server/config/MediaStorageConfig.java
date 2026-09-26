package com.veritae.veritae_server.config;

import com.veritae.veritae_server.media.DisabledMediaStorage;
import com.veritae.veritae_server.media.MediaStorage;
import com.veritae.veritae_server.media.MediaStorageProperties;
import com.veritae.veritae_server.media.R2MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * 원본 보관소 빈. R2 설정이 다 있으면 R2 에 연결하고, 하나라도 없으면 보관 기능을 끈 채로 뜬다.
 * R2 연결 설정은 Cloudflare 공식 문서(AWS SDK for Java v2 예제)를 그대로 따른다:
 * endpoint https://{accountId}.r2.cloudflarestorage.com, region "auto", path-style 접근,
 * chunkedEncoding 끔(켜져 있으면 업로드가 서명 불일치 403 으로 거부된다고 문서에 명시), 체크섬은 필요할 때만.
 */
@Slf4j
@Configuration
public class MediaStorageConfig {

    @Bean
    public MediaStorage mediaStorage(MediaStorageProperties properties) {
        if (!properties.configured()) {
            log.warn("R2 설정(R2_ACCOUNT_ID/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET)이 없어 "
                    + "분석 원본 보관 기능을 끈 상태로 시작합니다.");
            return new DisabledMediaStorage();
        }
        S3Client s3Client = r2Client(
                URI.create("https://" + properties.accountId() + ".r2.cloudflarestorage.com"),
                properties.accessKeyId(), properties.secretAccessKey());
        // R2MediaStorage 는 AutoCloseable 이라 서버 종료 시 Spring 이 close()를 불러 R2 클라이언트를 닫는다.
        return new R2MediaStorage(s3Client, properties.bucket());
    }

    /**
     * R2 용 S3 클라이언트. endpoint 만 받게 분리한 이유는, 테스트(R2MediaStorageWireTest)가 가짜 서버로
     * 연결해 이 설정 그대로 실제로 어떤 HTTP 요청이 나가는지 확인하기 위해서다.
     */
    static S3Client r2Client(URI endpoint, String accessKeyId, String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                // 최신 AWS SDK 는 업로드마다 체크섬을 기본으로 붙이는데, Cloudflare 가 이 기본 동작이 R2 와
                // 맞지 않는다며 필요할 때만 쓰도록(WHEN_REQUIRED) 설정하라고 안내한다(R2 문서의 AWS SDK JS v3
                // 예제 - Java SDK 도 같은 시기에 같은 기본값으로 바뀌었다).
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
