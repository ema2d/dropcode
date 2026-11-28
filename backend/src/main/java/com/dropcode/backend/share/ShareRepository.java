package com.dropcode.backend.share;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis storage for share metadata.
 * <ul>
 *   <li>{@code share:{code}} — Hash with the share fields, TTL = share lifetime</li>
 *   <li>{@code expiry:index} — Sorted set of objectKeys scored by expiresAt (epoch ms),
 *       so the cleanup job can delete R2 objects after the Redis key has expired</li>
 * </ul>
 * Multi-step writes use Lua scripts so they are atomic.
 */
@Repository
class ShareRepository {

    static final String EXPIRY_INDEX = "expiry:index";

    private static final RedisScript<Long> CREATE =
            RedisScript.of(new ClassPathResource("scripts/share-create.lua"), Long.class);
    private static final RedisScript<Long> MARK_READY =
            RedisScript.of(new ClassPathResource("scripts/share-mark-ready.lua"), Long.class);
    private static final RedisScript<Long> CONSUME_DOWNLOAD =
            RedisScript.of(new ClassPathResource("scripts/share-consume-download.lua"), Long.class);

    private final StringRedisTemplate redis;

    ShareRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String code) {
        return "share:" + code;
    }

    /**
     * Stores a new share if its code is not taken.
     *
     * @return {@code false} on code collision — the caller should generate a new code and retry
     */
    boolean createIfAbsent(Share share, Duration ttl) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(ttl.toMillis()));
        args.add(share.objectKey());
        args.add(String.valueOf(share.expiresAt().toEpochMilli()));
        toHash(share).forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });

        Long created = redis.execute(CREATE, List.of(key(share.code()), EXPIRY_INDEX), args.toArray());
        return created != null && created == 1L;
    }

    Optional<Share> find(String code) {
        Map<Object, Object> hash = redis.opsForHash().entries(key(code));
        return hash.isEmpty() ? Optional.empty() : Optional.of(fromHash(code, hash));
    }

    /**
     * Marks an uploaded share as READY with its final expiry.
     *
     * @return {@code false} if the share does not exist or is not PENDING
     */
    boolean markReady(String code, Instant expiresAt) {
        Long updated = redis.execute(MARK_READY, List.of(key(code), EXPIRY_INDEX),
                String.valueOf(expiresAt.toEpochMilli()));
        return updated != null && updated == 1L;
    }

    /**
     * Atomically uses up one download. Safe under concurrency: with {@code remaining = N},
     * exactly N callers succeed no matter how many arrive at once.
     * When the last download is consumed the share key is deleted immediately and the
     * R2 object is re-scheduled in {@code expiry:index} for deletion at {@code objectDeleteAt}.
     *
     * @return downloads left after this one, or empty if the share is missing, not READY, or used up
     */
    OptionalInt consumeDownload(String code, Instant objectDeleteAt) {
        Long left = redis.execute(CONSUME_DOWNLOAD, List.of(key(code), EXPIRY_INDEX),
                String.valueOf(objectDeleteAt.toEpochMilli()));
        return left == null || left < 0 ? OptionalInt.empty() : OptionalInt.of(left.intValue());
    }

    void delete(String code) {
        redis.delete(key(code));
    }

    private static Map<String, String> toHash(Share s) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("objectKey", s.objectKey());
        hash.put("size", String.valueOf(s.size()));
        hash.put("status", s.status().name());
        hash.put("remaining", String.valueOf(s.remaining()));
        hash.put("protected", String.valueOf(s.passwordProtected()));
        putIfNotNull(hash, "authHash", s.authHash());
        putIfNotNull(hash, "salt", s.salt());
        putIfNotNull(hash, "encMeta", s.encMeta());
        hash.put("failedAttempts", String.valueOf(s.failedAttempts()));
        hash.put("expiresAt", String.valueOf(s.expiresAt().toEpochMilli()));
        return hash;
    }

    private static void putIfNotNull(Map<String, String> hash, String field, String value) {
        if (value != null) {
            hash.put(field, value);
        }
    }

    private static Share fromHash(String code, Map<Object, Object> h) {
        return new Share(
                code,
                (String) h.get("objectKey"),
                Long.parseLong((String) h.get("size")),
                ShareStatus.valueOf((String) h.get("status")),
                Integer.parseInt((String) h.get("remaining")),
                Boolean.parseBoolean((String) h.get("protected")),
                (String) h.get("authHash"),
                (String) h.get("salt"),
                (String) h.get("encMeta"),
                Integer.parseInt((String) h.get("failedAttempts")),
                Instant.ofEpochMilli(Long.parseLong((String) h.get("expiresAt"))));
    }
}
