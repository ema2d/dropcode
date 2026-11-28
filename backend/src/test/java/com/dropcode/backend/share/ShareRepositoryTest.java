package com.dropcode.backend.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.dropcode.backend.TestcontainersConfiguration;

/** Runs against a real Redis in Docker (Testcontainers), not a mock — Lua scripts need a real server. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShareRepositoryTest {

    private static final Duration PENDING_TTL = Duration.ofMinutes(15);

    @Autowired
    private ShareRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void createIfAbsent_thenFind_roundTripsAllFields() {
        Share share = protectedShare("ABC23");

        assertThat(repository.createIfAbsent(share, PENDING_TTL)).isTrue();

        assertThat(repository.find("ABC23")).contains(share);
    }

    @Test
    void createIfAbsent_handlesNullOptionalFields() {
        Share share = pendingShare("ABC23", "objects/1");

        repository.createIfAbsent(share, PENDING_TTL);

        Share found = repository.find("ABC23").orElseThrow();
        assertThat(found).isEqualTo(share);
        assertThat(found.authHash()).isNull();
        assertThat(redis.opsForHash().hasKey(ShareRepository.key("ABC23"), "authHash")).isFalse();
    }

    @Test
    void createIfAbsent_returnsFalseOnCollision_andKeepsOriginal() {
        Share original = pendingShare("ABC23", "objects/original");
        Share intruder = pendingShare("ABC23", "objects/intruder");

        assertThat(repository.createIfAbsent(original, PENDING_TTL)).isTrue();
        assertThat(repository.createIfAbsent(intruder, PENDING_TTL)).isFalse();

        assertThat(repository.find("ABC23")).map(Share::objectKey).contains("objects/original");
    }

    @Test
    void createIfAbsent_isAtomicUnderConcurrency() throws Exception {
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Share share = pendingShare("RACE2", "objects/" + i);
            tasks.add(() -> {
                start.await();
                return repository.createIfAbsent(share, PENDING_TTL);
            });
        }

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            long winners = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void createIfAbsent_setsPendingTtlAndIndexesObjectKey() {
        Share share = pendingShare("ABC23", "objects/1");

        repository.createIfAbsent(share, PENDING_TTL);

        Long ttlSeconds = redis.getExpire(ShareRepository.key("ABC23"), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(PENDING_TTL.toSeconds() - 5, PENDING_TTL.toSeconds());
        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, "objects/1"))
                .isEqualTo((double) share.expiresAt().toEpochMilli());
    }

    @Test
    void markReady_updatesStatusTtlAndIndex() {
        repository.createIfAbsent(pendingShare("ABC23", "objects/1"), PENDING_TTL);
        Instant finalExpiry = Instant.now().plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MILLIS);

        assertThat(repository.markReady("ABC23", finalExpiry)).isTrue();

        Share found = repository.find("ABC23").orElseThrow();
        assertThat(found.status()).isEqualTo(ShareStatus.READY);
        assertThat(found.expiresAt()).isEqualTo(finalExpiry);
        assertThat(redis.getExpire(ShareRepository.key("ABC23"), TimeUnit.SECONDS))
                .isCloseTo(Duration.ofHours(24).toSeconds(), within(5L));
        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, "objects/1"))
                .isEqualTo((double) finalExpiry.toEpochMilli());
    }

    @Test
    void markReady_returnsFalseWhenMissingOrAlreadyReady() {
        Instant expiry = Instant.now().plus(Duration.ofHours(1));
        assertThat(repository.markReady("NOPE2", expiry)).isFalse();
        assertThat(redis.hasKey(ShareRepository.key("NOPE2"))).isFalse();

        repository.createIfAbsent(pendingShare("ABC23", "objects/1"), PENDING_TTL);
        assertThat(repository.markReady("ABC23", expiry)).isTrue();
        assertThat(repository.markReady("ABC23", expiry)).isFalse();
    }

    @Test
    void find_returnsEmptyForUnknownCode() {
        assertThat(repository.find("NOPE2")).isEmpty();
    }

    @Test
    void delete_removesShare() {
        repository.createIfAbsent(pendingShare("ABC23", "objects/1"), PENDING_TTL);

        repository.delete("ABC23");

        assertThat(repository.find("ABC23")).isEmpty();
    }

    private static Share pendingShare(String code, String objectKey) {
        return new Share(code, objectKey, 1024, ShareStatus.PENDING, 1, false,
                null, null, "ZW5jTWV0YQ==", "ZmlsZUtleQ==", 0, Duration.ofHours(24), expiresIn15Minutes());
    }

    private static Share protectedShare(String code) {
        return new Share(code, "objects/uuid-1", 2048, ShareStatus.PENDING, 3, true,
                "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA", "c2FsdC1iYXNlNjQ=", "ZW5jTWV0YQ==",
                null, 0, Duration.ofDays(7), expiresIn15Minutes());
    }

    private static Instant expiresIn15Minutes() {
        // Redis stores epoch millis, so drop sub-millisecond precision before comparing
        return Instant.now().plus(PENDING_TTL).truncatedTo(ChronoUnit.MILLIS);
    }
}
