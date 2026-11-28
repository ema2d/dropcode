# 3. Client-side encryption

**Status:** accepted

## Context
Users share arbitrary files. The operator (and anyone who compromises R2 or Redis) shouldn't be able to read protected files.

## Decision
The browser encrypts with AES-256-GCM (Web Crypto) before uploading. With a password, PBKDF2-SHA256 (600k iterations, random salt) derives 64 bytes: the AES key and a separate `authToken`. Only the token goes to the server, which stores an Argon2id hash of it and checks it before giving out a download URL. Without a password, a random key is stored server-side. That isn't end-to-end, and the UI says so.

## Consequences
- The server can't decrypt protected files and never learns the password.
- The salt is public (`GET /shares/{code}`): the downloader needs it before deriving the token.
- File names are encrypted too and sanitised before saving.
