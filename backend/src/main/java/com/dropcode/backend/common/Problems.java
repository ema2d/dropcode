package com.dropcode.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Builds RFC 9457 {@link ProblemDetail} errors. Spring MVC renders an
 * {@link ErrorResponseException} as {@code application/problem+json} automatically.
 * Each problem carries a stable machine-readable {@code code} the frontend can translate.
 */
public final class Problems {

    private Problems() {
    }

    public static ErrorResponseException of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
