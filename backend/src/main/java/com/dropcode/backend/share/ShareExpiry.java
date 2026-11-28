package com.dropcode.backend.share;

import java.time.Duration;
import java.util.Arrays;

/** Lifetimes the uploader can pick. The API uses the short labels ("10m", "1h", "24h", "7d"). */
enum ShareExpiry {
    TEN_MINUTES("10m", Duration.ofMinutes(10)),
    ONE_HOUR("1h", Duration.ofHours(1)),
    ONE_DAY("24h", Duration.ofHours(24)),
    SEVEN_DAYS("7d", Duration.ofDays(7));

    static final ShareExpiry DEFAULT = ONE_DAY;

    private final String label;
    private final Duration duration;

    ShareExpiry(String label, Duration duration) {
        this.label = label;
        this.duration = duration;
    }

    Duration duration() {
        return duration;
    }

    static ShareExpiry fromLabel(String label) {
        if (label == null) {
            return DEFAULT;
        }
        return Arrays.stream(values())
                .filter(e -> e.label.equals(label))
                .findFirst()
                .orElseThrow(() -> ShareErrors.badRequest("expiry must be one of 10m, 1h, 24h, 7d"));
    }
}
