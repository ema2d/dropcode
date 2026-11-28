# 4. Wrong passwords never lock a share

**Status:** accepted

## Context
The original plan was to lock and delete a share after 5 wrong passwords. But one share is often sent to several people: one of them mistyping the password, or anyone who learns the code, could destroy it for everyone else.

## Decision
Wrong passwords are limited **per client IP** (5 per 15 minutes). The share itself is never locked. Guessing is also slowed by per-IP lookup limits and Turnstile after repeated unknown codes. The `authToken` comes from a 600k-iteration PBKDF2, so offline guessing isn't possible without the server.

## Consequences
- No denial of service against legitimate recipients.
- An attacker with many IPs gets more guesses; strong passwords and short expiries limit the risk.
