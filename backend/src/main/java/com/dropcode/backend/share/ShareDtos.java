package com.dropcode.backend.share;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request and response bodies for {@code /api/v1/shares}. */
final class ShareDtos {

    static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final String BASE64 = "^[A-Za-z0-9+/=_-]*$";

    private ShareDtos() {
    }

    /**
     * @param downloads 1, 3, 5 or 10 (default 1)
     * @param expiry    "10m", "1h", "24h" or "7d" (default "24h")
     * @param authToken protected only: last 32 bytes of the PBKDF2 output (Base64)
     * @param salt      protected only: PBKDF2 salt (Base64)
     * @param fileKey   unprotected only: random AES key (Base64)
     */
    record CreateShareRequest(
            @Positive @Max(MAX_FILE_SIZE) long size,
            Integer downloads,
            String expiry,
            @JsonProperty("protected") boolean passwordProtected,
            @NotBlank @Size(max = 2048) @Pattern(regexp = BASE64) String encMeta,
            @Size(max = 128) @Pattern(regexp = BASE64) String authToken,
            @Size(max = 64) @Pattern(regexp = BASE64) String salt,
            @Size(max = 128) @Pattern(regexp = BASE64) String fileKey) {
    }

    record UploadTarget(URI url, Map<String, String> headers, Instant expiresAt) {
    }

    record CreateShareResponse(String code, UploadTarget upload) {
    }

    record CompleteShareResponse(String code, Instant expiresAt, int downloadsLeft) {
    }

    /** {@code salt} is public: the downloader needs it to derive the key from the password. */
    record ShareInfoResponse(
            @JsonProperty("protected") boolean passwordProtected,
            long size,
            Instant expiresAt,
            String salt) {
    }

    record DownloadRequest(@Size(max = 128) @Pattern(regexp = BASE64) String authToken) {
    }

    record DownloadResponse(URI url, String encMeta, String fileKey, int downloadsLeft) {
    }
}
