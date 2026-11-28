package com.dropcode.backend.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Calls Cloudflare's siteverify API. Fails closed: any error counts as "not verified". */
@Component
class CloudflareTurnstileVerifier implements TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(CloudflareTurnstileVerifier.class);
    private static final String SITEVERIFY = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RestClient rest;
    private final String secret;

    CloudflareTurnstileVerifier(AbuseProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.secret = props.turnstileSecretKey();
        if (AbuseProperties.TURNSTILE_TEST_SECRET.equals(secret)) {
            log.warn("Turnstile is using Cloudflare's TEST secret (every token passes). "
                    + "Set TURNSTILE_SECRET_KEY in production.");
        }
    }

    @Override
    public boolean verify(String token, String clientIp) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return false;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secret);
        form.add("response", token);
        form.add("remoteip", clientIp);
        try {
            SiteverifyResponse response = rest.post().uri(SITEVERIFY)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteverifyResponse.class);
            return response != null && response.success();
        } catch (RuntimeException e) {
            log.warn("Turnstile verification failed: {}", e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SiteverifyResponse(boolean success) {
    }
}
