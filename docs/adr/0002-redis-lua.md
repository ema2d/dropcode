# 2. Redis + Lua for atomic share state

**Status:** accepted

## Context
Several operations are read-check-write: "create only if the code is free", "PENDING → READY", "decrement only if downloads remain". Done as separate commands, two concurrent requests can both pass the check (two downloads of a one-download share).

## Decision
Shares are Redis hashes (`share:{code}`) with a TTL. Each multi-step operation is a Lua script, which Redis runs atomically. The same approach is used for rate-limit counters (`INCR` + `PEXPIRE` together). A sorted set `expiry:index` (score = time to delete) tells the cleanup job which R2 objects to remove, because R2 objects don't expire with the Redis key.

## Consequences
- Tested with 50 concurrent creates on one code (exactly one wins) and 100 concurrent downloads of a 10-download share (exactly 10 win).
- Own small rate limiter instead of Bucket4j: fewer moving parts, same pattern as the rest.
- An R2 lifecycle rule (delete after 8 days) is the safety net if the cleanup job misses something.
