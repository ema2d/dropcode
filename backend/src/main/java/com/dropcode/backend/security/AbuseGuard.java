package com.dropcode.backend.security;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

import com.dropcode.backend.common.Problems;

/**
 * Per-IP abuse protection for the share API.
 * <ul>
 *   <li>creates: {@code createsPerHour}</li>
 *   <li>lookups (info / complete / download): {@code lookupsPerMinute} and {@code lookupsPerHour}</li>
 *   <li>after {@code badCodesBeforeCaptcha} unknown codes in an hour, a Turnstile token is required</li>
 *   <li>wrong passwords: {@code wrongPasswordsPer15Minutes}, then this IP waits. The share itself is
 *       never locked, so one recipient typing the password wrong cannot block the others.</li>
 * </ul>
 */
@Component
public class AbuseGuard {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration QUARTER_HOUR = Duration.ofMinutes(15);

    private final RateLimiter limiter;
    private final TurnstileVerifier turnstile;
    private final AbuseProperties props;

    AbuseGuard(RateLimiter limiter, TurnstileVerifier turnstile, AbuseProperties props) {
        this.limiter = limiter;
        this.turnstile = turnstile;
        this.props = props;
    }

    public void checkCreate(String ip) {
        enforce("rl:create:h:" + ip, HOUR, props.createsPerHour());
    }

    public void checkLookup(String ip) {
        enforce("rl:lookup:m:" + ip, MINUTE, props.lookupsPerMinute());
        enforce("rl:lookup:h:" + ip, HOUR, props.lookupsPerHour());
    }

    /** Requires a valid Turnstile token once this IP has tried too many unknown codes. */
    public void checkCaptcha(String ip, String turnstileToken) {
        String key = badCodesKey(ip);
        if (limiter.count(key) < props.badCodesBeforeCaptcha()) {
            return;
        }
        if (!turnstile.verify(turnstileToken, ip)) {
            throw Problems.of(HttpStatus.FORBIDDEN, "CAPTCHA_REQUIRED", "Verification required",
                    "Too many unknown codes. Complete the verification and try again.");
        }
        // Tokens are single-use: a solved challenge gives this IP a fresh set of tries
        limiter.reset(key);
    }

    public void recordBadCode(String ip) {
        limiter.hit(badCodesKey(ip), HOUR);
    }

    public void checkPasswordAttempts(String ip) {
        String key = wrongPasswordsKey(ip);
        if (limiter.count(key) >= props.wrongPasswordsPer15Minutes()) {
            throw tooMany(key);
        }
    }

    public void recordWrongPassword(String ip) {
        limiter.hit(wrongPasswordsKey(ip), QUARTER_HOUR);
    }

    private void enforce(String key, Duration window, int limit) {
        if (limiter.hit(key, window) > limit) {
            throw tooMany(key);
        }
    }

    private ErrorResponseException tooMany(String key) {
        Duration retryAfter = limiter.retryAfter(key);
        ErrorResponseException e = Problems.of(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
                "Please wait before trying again.");
        e.getBody().setProperty("retryAfterSeconds", retryAfter.toSeconds());
        e.getHeaders().set("Retry-After", String.valueOf(retryAfter.toSeconds()));
        return e;
    }

    private static String badCodesKey(String ip) {
        return "rl:badcode:" + ip;
    }

    private static String wrongPasswordsKey(String ip) {
        return "rl:badpw:" + ip;
    }
}
