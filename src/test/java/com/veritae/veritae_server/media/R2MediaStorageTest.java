package com.veritae.veritae_server.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
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

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class R2MediaStorageTest {

    private static final String BUCKET = "veritae-media";

    @Mock
    private S3Client s3Client;

    private R2MediaStorage storage;

    @BeforeEach
    void setUp() {
        storage = new R2MediaStorage(s3Client, BUCKET);
    }

    @Test
    void put_shouldUploadBytesWithContentTypeToBucketKey() {
        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);

        storage.put("media/m/r", "abc".getBytes(), "image/jpeg");

        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("media/m/r");
        assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().contentLength()).isEqualTo(3L);
    }

    @Test
    void get_withoutRange_shouldReturnWholeObjectWithoutContentRange() throws Exception {
        var captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        when(s3Client.getObject(captor.capture())).thenReturn(stream("hello",
                GetObjectResponse.builder().contentType("video/mp4").contentLength(5L).build()));

        MediaObject object = storage.get("media/m/r", null);

        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("media/m/r");
        assertThat(captor.getValue().range()).isNull();
        assertThat(object.contentType()).isEqualTo("video/mp4");
        assertThat(object.contentLength()).isEqualTo(5L);
        assertThat(object.contentRange()).isNull();
        assertThat(object.partial()).isFalse();
        assertThat(object.content().readAllBytes()).isEqualTo("hello".getBytes());
    }

    @Test
    void get_withRange_shouldPassRangeToR2AndReturnPartialObject() {
        var captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        when(s3Client.getObject(captor.capture())).thenReturn(stream("he",
                GetObjectResponse.builder().contentType("video/mp4").contentLength(2L)
                        .contentRange("bytes 0-1/5").build()));

        MediaObject object = storage.get("media/m/r", "bytes=0-1");

        assertThat(captor.getValue().range()).isEqualTo("bytes=0-1");
        assertThat(object.contentRange()).isEqualTo("bytes 0-1/5");
        assertThat(object.contentLength()).isEqualTo(2L);
        assertThat(object.partial()).isTrue();
    }

    @Test
    void get_whenObjectMissing_shouldThrowMediaObjectNotFoundException() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThatThrownBy(() -> storage.get("media/m/r", null))
                .isInstanceOf(MediaObjectNotFoundException.class);
    }

    @Test
    void get_whenRangeNotSatisfiable_shouldThrowMediaRangeNotSatisfiableException() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(416).build());

        assertThatThrownBy(() -> storage.get("media/m/r", "bytes=999-"))
                .isInstanceOf(MediaRangeNotSatisfiableException.class);
    }

    @Test
    void delete_shouldDeleteBucketKey() {
        var captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        storage.delete("media/m/r");

        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("media/m/r");
    }

    @Test
    void deleteByPrefix_shouldDeleteEveryObjectAcrossAllPages() {
        var listCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        when(s3Client.listObjectsV2(listCaptor.capture()))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("media/m/1").build(), S3Object.builder().key("media/m/2").build())
                        .isTruncated(true).nextContinuationToken("page2").build())
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("media/m/3").build())
                        .isTruncated(false).build());
        var deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);

        storage.deleteByPrefix("media/m/");

        assertThat(listCaptor.getAllValues()).extracting(ListObjectsV2Request::prefix).containsOnly("media/m/");
        assertThat(listCaptor.getAllValues().get(1).continuationToken()).isEqualTo("page2");
        verify(s3Client, times(3)).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly("media/m/1", "media/m/2", "media/m/3");
    }

    @Test
    void deleteByPrefix_withBlankPrefix_shouldRefuseInsteadOfDeletingWholeBucket() {
        assertThatThrownBy(() -> storage.deleteByPrefix(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.deleteByPrefix(null))
                .isInstanceOf(IllegalArgumentException.class);
        // "media/abc" 로 지우면 "media/abcdef/..." 같은 다른 폴더까지 걸린다 - 반드시 '/'로 끝나야 한다.
        assertThatThrownBy(() -> storage.deleteByPrefix("media/abc"))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(s3Client);
    }

    private static ResponseInputStream<GetObjectResponse> stream(String body, GetObjectResponse response) {
        return new ResponseInputStream<>(response,
                AbortableInputStream.create(new ByteArrayInputStream(body.getBytes())));
    }
}
