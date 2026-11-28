package com.dropcode.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Offline unit test: presigning is pure local crypto (no network call),
 * so we use a real S3Presigner with dummy credentials and mock the S3Client.
 */
class R2StorageServiceTest {

    private static final StorageProperties PROPS =
            new StorageProperties("acc123", "test-key", "test-secret", "my-bucket");

    private S3Presigner presigner;
    private R2StorageService service;

    @BeforeEach
    void setUp() {
        presigner = new StorageConfig().s3Presigner(PROPS);
        service = new R2StorageService(mock(S3Client.class), presigner, PROPS);
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
}
