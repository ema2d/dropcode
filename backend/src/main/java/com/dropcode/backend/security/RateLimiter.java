package com.dropcode.backend.security;

import java.time.Duration;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis ({@code rl:*}). Shared by all app instances and
 * survives restarts. INCR + PEXPIRE run in one Lua script, so a counter can never
 * be left without an expiry.
 */
@Component
class RateLimiter {

    private static final RedisScript<Long> HIT =
            RedisScript.of(new ClassPathResource("scripts/rate-limit-hit.lua"), Long.class);

    private final StringRedisTemplate redis;

    RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts one hit; returns the count in the current window. */
    long hit(String key, Duration window) {
        Long count = redis.execute(HIT, List.of(key), String.valueOf(window.toMillis()));
        return count == null ? 0 : count;
    }

    long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** Time until the window resets (at least 1 second). */
    Duration retryAfter(String key) {
        Long ms = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        return Duration.ofMillis(ms == null || ms < 1000 ? 1000 : ms);
    }

    void reset(String key) {
        redis.delete(key);
    }
}
