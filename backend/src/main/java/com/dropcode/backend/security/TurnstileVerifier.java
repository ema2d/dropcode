package com.dropcode.backend.security;

/** Checks a Cloudflare Turnstile token (proof that a human solved the challenge). */
public interface TurnstileVerifier {

    boolean verify(String token, String clientIp);
}
