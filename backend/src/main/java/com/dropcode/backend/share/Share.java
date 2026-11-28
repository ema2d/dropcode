package com.dropcode.backend.share;

import java.time.Instant;

/**
 * Metadata for one share, stored in Redis as Hash {@code share:{code}}.
 * The file itself lives in R2 under {@code objectKey}; this record never holds file bytes.
 *
 * @param authHash  Argon2id hash of the client's authToken; null when not password-protected
 * @param salt      PBKDF2 salt (Base64) the downloader needs to derive the key; null when not protected
 * @param encMeta   encrypted file name / type (Base64), opaque to the server
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
        int failedAttempts,
        Instant expiresAt) {
}
