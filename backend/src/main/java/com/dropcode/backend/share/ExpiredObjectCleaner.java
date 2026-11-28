package com.dropcode.backend.share;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dropcode.backend.storage.StorageService;

/**
 * Deletes R2 objects whose share expired or was used up.
 * Redis expires {@code share:{code}} on its own, but R2 does not — {@code expiry:index}
 * remembers which objects to delete and when. An R2 lifecycle rule (8 days) is the safety net.
 */
@Component
class ExpiredObjectCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredObjectCleaner.class);
    static final int BATCH_SIZE = 100;

    private final ShareRepository repository;
    private final StorageService storage;
    private final Clock clock;

    ExpiredObjectCleaner(ShareRepository repository, StorageService storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${dropcode.cleanup.interval:PT5M}",
            initialDelayString = "${dropcode.cleanup.initial-delay:PT1M}")
    void scheduledCleanUp() {
        int deleted = cleanUp();
        if (deleted > 0) {
            log.info("Deleted {} expired objects from storage", deleted);
        }
    }

    /** @return number of objects deleted */
    int cleanUp() {
        Instant now = clock.instant();
        int deleted = 0;
        List<String> due;
        do {
            due = repository.findDueObjectKeys(now, BATCH_SIZE);
            int deletedInBatch = 0;
            for (String objectKey : due) {
                try {
                    storage.delete(objectKey);
                    // Only remove after R2 confirmed, so a failure is retried next run
                    repository.removeFromExpiryIndexIfDue(objectKey, now);
                    deletedInBatch++;
                } catch (RuntimeException e) {
                    log.warn("Could not delete {} from storage, will retry next run", objectKey, e);
                }
            }
            deleted += deletedInBatch;
            // Stop if a whole batch failed (e.g. R2 down) instead of looping forever
            if (deletedInBatch == 0) {
                break;
            }
        } while (due.size() == BATCH_SIZE);
        return deleted;
    }
}
