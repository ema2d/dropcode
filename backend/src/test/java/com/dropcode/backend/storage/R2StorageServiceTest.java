package com.dropcode.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Offline unit test: presigning is pure local crypto (no network call),
 * so we use a real S3Presigner with dummy credentials and mock the S3Client.
 */
class R2StorageServiceTest {

    private static final StorageProperties PROPS =
            new StorageProperties("acc123", "test-key", "test-secret", "my-bucket");

    private S3Presigner presigner;
    private S3Client client;
    private R2StorageService service;

    @BeforeEach
    void setUp() {
        presigner = new StorageConfig().s3Presigner(PROPS);
        client = mock(S3Client.class);
        service = new R2StorageService(client, presigner, PROPS);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void presignUpload_buildsPathStyleR2UrlWithTenMinuteExpiry() {
        PresignedUpload upload = service.presignUpload("objects/abc", 1024, Duration.ofMinutes(10));

        URI url = upload.url();
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("acc123.r2.cloudflarestorage.com");
        assertThat(url.getPath()).isEqualTo("/my-bucket/objects/abc");
        assertThat(url.getQuery()).contains("X-Amz-Expires=600");
    }

    @Test
    void presignUpload_excludesHeadersTheBrowserSetsItself() {
        PresignedUpload upload = service.presignUpload("objects/abc", 1024, Duration.ofMinutes(10));

        assertThat(upload.headers().keySet())
                .map(String::toLowerCase)
                .doesNotContain("host", "content-length");
    }

    @Test
    void presignUpload_signsContentLength() {
        PresignedUpload upload = service.presignUpload("objects/abc", 1024, Duration.ofMinutes(10));

        // content-length is part of the signature, so R2 rejects a body of a different size
        assertThat(upload.url().getQuery()).containsIgnoringCase("content-length");
    }

    @Test
    void presignDownload_buildsUrlWithSixtySecondExpiry() {
        URI url = service.presignDownload("objects/abc", Duration.ofSeconds(60));

        assertThat(url.getHost()).isEqualTo("acc123.r2.cloudflarestorage.com");
        assertThat(url.getPath()).isEqualTo("/my-bucket/objects/abc");
        assertThat(url.getQuery()).contains("X-Amz-Expires=60");
        assertThat(url.getQuery()).doesNotContain("X-Amz-Expires=600");
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectSize_returnsContentLength() {
        when(client.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(42L).build());

        assertThat(service.objectSize("objects/abc")).contains(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectSize_isEmptyWhenObjectIsMissing() {
        when(client.headObject(any(Consumer.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(service.objectSize("objects/abc")).isEmpty();

        // HEAD responses have no body, so R2 often reports "missing" as a plain 404 S3Exception
        when(client.headObject(any(Consumer.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(service.objectSize("objects/abc")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectSize_rethrowsOtherErrors() {
        when(client.headObject(any(Consumer.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> service.objectSize("objects/abc")).isInstanceOf(S3Exception.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void delete_targetsConfiguredBucket() {
        service.delete("objects/abc");

        var captor = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(client).deleteObject(captor.capture());
        DeleteObjectRequest.Builder builder = DeleteObjectRequest.builder();
        ((Consumer<DeleteObjectRequest.Builder>) captor.getValue()).accept(builder);
        assertThat(builder.build().bucket()).isEqualTo("my-bucket");
        assertThat(builder.build().key()).isEqualTo("objects/abc");
    }
}
