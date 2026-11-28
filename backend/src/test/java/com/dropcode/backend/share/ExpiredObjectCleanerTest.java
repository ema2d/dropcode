package com.dropcode.backend.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dropcode.backend.TestcontainersConfiguration;
import com.dropcode.backend.storage.StorageService;

/** Cleanup job against real Redis; R2 is mocked. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ExpiredObjectCleanerTest {

    @Autowired
    private ExpiredObjectCleaner cleaner;

    @Autowired
    private ShareRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private StorageService storage;

    @BeforeEach
    void flushRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void deletesOnlyDueObjects() {
        index("objects/old", Instant.now().minus(Duration.ofMinutes(1)));
        index("objects/future", Instant.now().plus(Duration.ofHours(1)));

        assertThat(cleaner.cleanUp()).isEqualTo(1);

        verify(storage).delete("objects/old");
        verify(storage, never()).delete("objects/future");
        assertThat(redis.opsForZSet().range(ShareRepository.EXPIRY_INDEX, 0, -1))
                .containsExactly("objects/future");
    }

    @Test
    void keepsEntryWhenStorageDeleteFails() {
        index("objects/old", Instant.now().minus(Duration.ofMinutes(1)));
        doThrow(new RuntimeException("R2 down")).when(storage).delete("objects/old");

        assertThat(cleaner.cleanUp()).isZero();

        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, "objects/old")).isNotNull();
    }

    @Test
    void processesMoreThanOneBatch() {
        int total = ExpiredObjectCleaner.BATCH_SIZE + 20;
        for (int i = 0; i < total; i++) {
            index("objects/" + i, Instant.now().minus(Duration.ofMinutes(1)));
        }

        assertThat(cleaner.cleanUp()).isEqualTo(total);
        assertThat(redis.opsForZSet().size(ShareRepository.EXPIRY_INDEX)).isZero();
    }

    @Test
    void doesNotRemoveEntryRescheduledIntoTheFuture() {
        Instant now = Instant.now();
        index("objects/x", now.plus(Duration.ofHours(1)));

        assertThat(repository.removeFromExpiryIndexIfDue("objects/x", now)).isFalse();
        assertThat(redis.opsForZSet().score(ShareRepository.EXPIRY_INDEX, "objects/x")).isNotNull();
    }

    private void index(String objectKey, Instant dueAt) {
        redis.opsForZSet().add(ShareRepository.EXPIRY_INDEX, objectKey,
                dueAt.truncatedTo(ChronoUnit.MILLIS).toEpochMilli());
    }
}
