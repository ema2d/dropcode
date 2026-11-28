package com.dropcode.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Abuse limits, all per client IP.
 *
 * @param trustCloudflareHeader read the client IP from {@code CF-Connecting-IP}. Enable only when
 *                              every request reaches the app through Cloudflare, otherwise clients
 *                              could fake their IP.
 * @param turnstileSecretKey    Cloudflare Turnstile secret. The default is Cloudflare's public
 *                              "always passes" test key — set the real one in production.
 */
@ConfigurationProperties(prefix = "dropcode.abuse")
public record AbuseProperties(
        @DefaultValue("10") int createsPerHour,
        @DefaultValue("10") int lookupsPerMinute,
        @DefaultValue("50") int lookupsPerHour,
        @DefaultValue("3") int badCodesBeforeCaptcha,
        @DefaultValue("5") int wrongPasswordsPer15Minutes,
        @DefaultValue("false") boolean trustCloudflareHeader,
        @DefaultValue(AbuseProperties.TURNSTILE_TEST_SECRET) String turnstileSecretKey) {

    /** Cloudflare's documented test secret: every token passes. Never use in production. */
    public static final String TURNSTILE_TEST_SECRET = "1x0000000000000000000000000000000AA";
}
