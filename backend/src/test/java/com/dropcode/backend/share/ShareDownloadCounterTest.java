package com.dropcode.backend.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
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

/** Atomic download counter (share-consume-download.lua) against real Redis. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShareDownloadCounterTest {

    private static final String CODE = "DL234";
    private static final String OBJECT_KEY = "objects/dl-1";

    @Autowired
    private ShareRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    private final Instant deleteAt = Instant.now().plus(Duration.ofMinutes(2)).truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void flushRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void hundredParallelRequests_onlyAllowedCountSucceeds() throws Exception {
        int allowed = 10;
        int requests = 100;
        createReadyShare(allowed);

        CountDownLatch start = new CountDownLatch(1);
        List<OptionalInt> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<OptionalInt>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return repository.consumeDownload(CODE, deleteAt);
                }));
            }
            start.countDown();
            for (Future<OptionalInt> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }
        }

        List<Integer> successes = results.stream()
                .filter(OptionalInt::isPresent)
                .map(OptionalInt::getAsInt)
                .toList();
        // Exactly 10 winners, each saw a different "left" value: 9, 8, ..., 0
        assertThat(successes).hasSize(allowed).containsExactlyInAnyOrder(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
        assertThat(redis.hasKey(ShareRepository.key(CODE))).isFalse();
        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, OBJECT_KEY))
                .isEqualTo((double) deleteAt.toEpochMilli());
    }

    @Test
    void download_decrementsAndKeepsShareWhileDownloadsRemain() {
        createReadyShare(3);
        Instant originalExpiry = repository.find(CODE).orElseThrow().expiresAt();

        assertThat(repository.consumeDownload(CODE, deleteAt)).hasValue(2);

        assertThat(repository.find(CODE)).map(Share::remaining).contains(2);
        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, OBJECT_KEY))
                .isEqualTo((double) originalExpiry.toEpochMilli());
    }

    @Test
    void lastDownload_deletesShareImmediately() {
        createReadyShare(1);

        assertThat(repository.consumeDownload(CODE, deleteAt)).hasValue(0);
        assertThat(repository.find(CODE)).isEmpty();
        assertThat(repository.consumeDownload(CODE, deleteAt)).isEmpty();
    }

    @Test
    void pendingShare_cannotBeDownloaded() {
        repository.createIfAbsent(share(ShareStatus.PENDING, 1), Duration.ofMinutes(15));

        assertThat(repository.consumeDownload(CODE, deleteAt)).isEmpty();
        assertThat(repository.find(CODE)).map(Share::remaining).contains(1);
    }

    @Test
    void unknownCode_returnsEmpty() {
        assertThat(repository.consumeDownload("NOPE2", deleteAt)).isEmpty();
        assertThat(redis.hasKey(ShareRepository.key("NOPE2"))).isFalse();
    }

    private void createReadyShare(int downloads) {
        repository.createIfAbsent(share(ShareStatus.PENDING, downloads), Duration.ofMinutes(15));
        repository.markReady(CODE, Instant.now().plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MILLIS));
    }

    private static Share share(ShareStatus status, int downloads) {
        return new Share(CODE, OBJECT_KEY, 1024, status, downloads, false, null, null, "bWV0YQ==", "a2V5", 0,
                Duration.ofHours(24), Instant.now().plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.MILLIS));
    }
}
