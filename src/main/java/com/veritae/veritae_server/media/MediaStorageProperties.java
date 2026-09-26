package com.veritae.veritae_server.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * media.r2.* 설정. 값은 모두 환경변수(R2_ACCOUNT_ID 등)로 받는다 - 키를 레포에 커밋하지 않기 위해서다.
 */
@ConfigurationProperties(prefix = "media.r2")
public record MediaStorageProperties(String accountId, String accessKeyId, String secretAccessKey, String bucket) {

    public boolean configured() {
        return notBlank(accountId) && notBlank(accessKeyId) && notBlank(secretAccessKey) && notBlank(bucket);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
