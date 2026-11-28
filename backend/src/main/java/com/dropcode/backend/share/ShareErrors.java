package com.dropcode.backend.share;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import com.dropcode.backend.common.Problems;

/** Share-specific errors (RFC 9457 ProblemDetail with a stable {@code code}). */
final class ShareErrors {

    private ShareErrors() {
    }

    /** Same response for unknown, expired, pending and used-up codes, so attackers learn nothing. */
    static ErrorResponseException notFound() {
        return Problems.of(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "Share not found",
                "No share exists for this code, or it has expired.");
    }

    static ErrorResponseException badRequest(String detail) {
        return Problems.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", detail);
    }

    static ErrorResponseException unauthorized() {
        return Problems.of(HttpStatus.UNAUTHORIZED, "WRONG_PASSWORD", "Wrong password",
                "The password for this share is not correct.");
    }

    static ErrorResponseException conflict(String detail) {
        return Problems.of(HttpStatus.CONFLICT, "UPLOAD_NOT_FINISHED", "Upload not finished", detail);
    }

    static ErrorResponseException unavailable(String detail) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", "Try again", detail);
    }
}
