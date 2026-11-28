package com.dropcode.backend.share;

import java.time.Duration;
import java.time.Instant;

/**
 * Metadata for one share, stored in Redis as Hash {@code share:{code}}.
 * The file itself lives in R2 under {@code objectKey}; this record never holds file bytes.
 *
 * @param authHash  Argon2id hash of the client's authToken; null when not password-protected
 * @param salt      PBKDF2 salt (Base64) the downloader needs to derive the key; null when not protected
 * @param encMeta   encrypted file name / type (Base64), opaque to the server
 * @param fileKey   AES key (Base64) for unprotected shares only; null when protected (key never leaves the device)
 * @param lifetime  how long the share lives once READY (chosen by the uploader: 10m / 1h / 24h / 7d)
 * @param expiresAt when the Redis key expires: upload deadline while PENDING, final expiry once READY
 */
public record Share(
        String code,
        String objectKey,
        long size,
        ShareStatus status,
        int remaining,
        boolean passwordProtected,
        String authHash,
        String salt,
        String encMeta,
        String fileKey,
        int failedAttempts,
        Duration lifetime,
        Instant expiresAt) {
}
