package com.dropcode.backend;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs. Turned off in tests with {@code dropcode.scheduling.enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "dropcode.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
