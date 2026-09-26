package com.veritae.veritae_server.media;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Cloudflare R2 구현. 저장된 파일은 R2 가 자동으로 암호화(AES-256)해서 보관한다 - 별도 암호화 코드가
 * 없는 이유. Range 요청은 R2 가 직접 처리하고, 여기서는 받은 Range 값을 그대로 넘기기만 한다.
 */
public class R2MediaStorage implements MediaStorage, AutoCloseable {

    private static final int RANGE_NOT_SATISFIABLE = 416;

    private final S3Client s3Client;
    private final String bucket;

    public R2MediaStorage(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public MediaObject get(String key, String range) {
        try {
            ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range(range)
                    .build());
            GetObjectResponse response = stream.response();
            return new MediaObject(stream, response.contentType(), response.contentLength(), response.contentRange());
        } catch (NoSuchKeyException e) {
            throw new MediaObjectNotFoundException(key);
        } catch (S3Exception e) {
            if (e.statusCode() == RANGE_NOT_SATISFIABLE) {
                throw new MediaRangeNotSatisfiableException(key, range, e);
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deleteByPrefix(String prefix) {
        // 빈 prefix 는 버킷 전체(모든 회원의 원본), '/' 로 안 끝나는 prefix 는 이름이 같은 글자로 시작하는
        // 다른 폴더까지 지운다 - 둘 다 실수 한 번으로 남의 파일이 사라지는 경우라 아예 거부한다.
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("prefix 는 비어 있지 않고 '/' 로 끝나야 합니다: " + prefix);
        }
        // 한 회원의 원본은 최대 10건 남짓이라 한 건씩 지운다(여러 건 한꺼번에 지우는 DeleteObjects 는
        // 요청 체크섬 방식이 R2 와 어긋날 수 있어 쓰지 않는다).
        String continuationToken = null;
        do {
            ListObjectsV2Response page = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            for (S3Object object : page.contents()) {
                delete(object.key());
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    @Override
    public void close() {
        s3Client.close();
    }
}
