package com.dropcode.backend.share;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Builds RFC 9457 {@link ProblemDetail} errors. Spring MVC renders an
 * {@link ErrorResponseException} as {@code application/problem+json} automatically.
 */
final class ShareErrors {

    private ShareErrors() {
    }

    /** Same response for unknown, expired, pending and used-up codes, so attackers learn nothing. */
    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "Share not found", "No share exists for this code, or it has expired.");
    }

    static ErrorResponseException badRequest(String detail) {
        return of(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    static ErrorResponseException unauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "Wrong password", "The password for this share is not correct.");
    }

    static ErrorResponseException conflict(String detail) {
        return of(HttpStatus.CONFLICT, "Upload not finished", detail);
    }

    static ErrorResponseException unavailable(String detail) {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "Try again", detail);
    }

    private static ErrorResponseException of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return new ErrorResponseException(status, problem, null);
    }
}
