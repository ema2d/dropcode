package com.dropcode.backend.share;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class ShareConfig {

    /** Injected instead of calling Instant.now() directly, so tests can freeze time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Argon2id for the client's authToken (salt 16 B, hash 32 B, 16 MiB memory, 2 iterations). */
    @Bean
    PasswordEncoder authTokenEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
