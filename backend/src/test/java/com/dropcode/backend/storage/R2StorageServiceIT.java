package com.dropcode.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test against the real R2 bucket (dropcode-dev).
 * Skipped unless R2 credentials are exported as environment variables, so CI stays offline.
 *
 * Run locally from backend/:
 *   set -a && source .env && set +a && ./gradlew test --tests '*R2StorageServiceIT'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "R2_ACCESS_KEY_ID", matches = ".+")
class R2StorageServiceIT {

    @Autowired
    private StorageService storage;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String objectKey = "objects/it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        // Safety net: never leave test objects behind in the bucket
        storage.delete(objectKey);
    }

    @Test
    void uploadViaPresignedUrl_thenHeadAndDelete() throws Exception {
        byte[] body = "hello from dropcode IT".getBytes(StandardCharsets.UTF_8);

        PresignedUpload upload = storage.presignUpload(objectKey, body.length, Duration.ofMinutes(10));

        HttpRequest.Builder request = HttpRequest.newBuilder(upload.url())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        upload.headers().forEach(request::header);

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("R2 PUT response body: %s", response.body())
                .isEqualTo(200);

        assertThat(storage.objectSize(objectKey)).contains((long) body.length);

        storage.delete(objectKey);
        assertThat(storage.objectSize(objectKey)).isEmpty();
    }

    @Test
    void uploadWithWrongSize_isRejectedBySignature() throws Exception {
        byte[] body = "twelve bytes".getBytes(StandardCharsets.UTF_8);

        // Presign for 5 bytes, then try to send 12 -> signature mismatch
        PresignedUpload upload = storage.presignUpload(objectKey, 5, Duration.ofMinutes(10));

        HttpRequest.Builder request = HttpRequest.newBuilder(upload.url())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        upload.headers().forEach(request::header);

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(storage.objectSize(objectKey)).isEmpty();
    }
}
