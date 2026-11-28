# Dropcode — project context for Claude

Secure cross-device file sharing with a 5-character code. Upload on one device, enter the code on another, download.
Owner: Emad (GitHub `ema2d`, repo `ema2d/dropcode`). This is a learning + portfolio (CV) project.

## How to work with me (read first)

- **Reply in Arabic** (Saudi-friendly, clear). Code, commands, file names stay in English.
- For every step, explain **why** in **both Arabic and English** (short: "عربي:" line + "English:" line). I am learning.
- For every operation, state **where** it happens: Terminal / VS Code / browser (which site) / GitHub / Cloudflare dashboard / Railway.
- One step at a time; wait for me to confirm or paste an error before moving on.
- Never write `<placeholder>` in commands I will copy (zsh treats `<` as input redirection — this already broke a `git clone`). Use real values or say "replace X".
- Teach good habits: feature branch per feature → PR → green CI → merge. Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`).
- Before every commit: `git status` and confirm `.env` is NOT listed.

## My environment

- macOS (Apple Silicon, arm64), zsh, **VS Code** (Java Extension Pack, Spring Boot Extension Pack, Gradle for Java, Docker).
- Java **25** (Temurin via `brew install --cask temurin@25`); Java 21 is also installed. Gradle toolchain = 25.
- Docker Desktop (must be running before `docker compose up -d`).
- Project path: `.../shareing/dropcode` (this repo root).

## Stack (decided)

| Layer | Choice |
|---|---|
| Backend | Java 25, Spring Boot 4.x (latest stable), Gradle Kotlin DSL, Jar packaging, YAML config |
| Metadata | Redis (TTL + atomic Lua counters) — Docker locally, Railway Redis in prod |
| File storage | Cloudflare R2 via AWS SDK for Java v2 (BOM 2.47.x), presigned URLs |
| Frontend (later) | React + Vite + TypeScript + Tailwind, PWA, Arabic (RTL) + English |
| Bot protection | Cloudflare Turnstile |
| Rate limiting | Bucket4j + Redis |
| Hosting | **Railway** (backend + Redis, Hobby plan, Serverless OFF, 512MB limit, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`), Cloudflare Pages (frontend), R2 (files) |
| CI | GitHub Actions (`.github/workflows/ci.yml`, Java 25, `./gradlew build`) |

Core principle: **files never pass through Spring Boot.** Browser uploads/downloads directly to/from R2 with short-lived presigned URLs; the API only handles codes, permissions, counters.

## Repo layout (actual)

```
dropcode/
├── .gitignore            # root: .env, .env.*, !.env.example, .DS_Store, .vscode/*, node_modules/, dist/
├── docker-compose.yml    # redis:7-alpine on 6379
├── .github/workflows/ci.yml
├── frontend/             # empty for now
└── backend/
    ├── .gitignore        # Gradle/Java (from start.spring.io)
    ├── .env              # local secrets, NOT committed
    ├── .env.example      # committed, names only
    ├── build.gradle.kts
    └── src/
        ├── main/java/com/dropcode/backend/
        │   ├── BackendApplication.java   # @SpringBootApplication @ConfigurationPropertiesScan
        │   ├── share/CodeGenerator.java
        │   └── storage/
        │       ├── StorageProperties.java   # record, prefix "storage", @Validated @NotBlank, endpoint()
        │       ├── StorageConfig.java       # S3Client + S3Presigner beans (region "auto", pathStyle true, chunkedEncoding false on client)
        │       ├── StorageService.java      # interface
        │       ├── PresignedUpload.java     # record(URI url, Map<String,String> headers)
        │       └── R2StorageService.java    # package-private @Service
        ├── main/resources/application.yaml
        └── test/java/com/dropcode/backend/
            ├── BackendApplicationTests.java
            └── share/CodeGeneratorTest.java
```

Base package is `com.dropcode.backend` (not `com.dropcode`).

`application.yaml` (note: .yaml, not .yml) contains: `spring.config.import: optional:file:.env[.properties]`, Redis host/port from env with defaults, `spring.threads.virtual.enabled: true`, actuator exposing only `health` (`show-details: always` for now — restrict before deploy), and:

```yaml
storage:
  account-id: ${R2_ACCOUNT_ID}
  access-key-id: ${R2_ACCESS_KEY_ID}
  secret-access-key: ${R2_SECRET_ACCESS_KEY}
  bucket: ${R2_BUCKET}
```

`.env` keys: `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET=dropcode-dev`. The R2 token is scoped to bucket `dropcode-dev`, Object Read & Write.

## Progress

Done and merged to `main`:
1. Project setup (Spring Boot, Docker Redis, health check UP, CI green).
2. `CodeGenerator` + tests: alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (31 chars, no 0/O/1/I/L), length 5 (~28.6M codes), `SecureRandom`, `normalize()` = trim + `toUpperCase(Locale.ROOT)`, `isValid()` regex.

3. Storage (PR #2): `StorageProperties`, `StorageConfig`, `R2StorageService` (presigned PUT with signed Content-Length, presigned GET, HeadObject, delete). Tests: `R2StorageServiceTest` (offline), `R2StorageServiceIT` (real R2, skipped unless `R2_ACCESS_KEY_ID` is set; run with `set -a && source .env && set +a && ./gradlew test --tests '*R2StorageServiceIT'` from `backend/`), `application-test.yml` test profile with dummy `storage.*` values.

4. ShareRepository (PR #3): `Share` Hash `share:{code}`, Lua `share-create.lua` (create only if code free + TTL + `expiry:index`) and `share-mark-ready.lua`. Testcontainers tests (`redis:7-alpine`).

5. Download counter (PR #4): `share-consume-download.lua` (READY + `remaining > 0` + decrement atomically; last download deletes the key and re-schedules the R2 object in `expiry:index` +2 min). 100-parallel test.
6. Share API (PR #5): `ShareService` + `ShareController` under `/api/v1/shares`, `ShareDtos`, `ShareErrors` (ProblemDetail via `ErrorResponseException`), `ShareExpiry`, `ShareConfig` (`Clock`, Argon2id `PasswordEncoder` from spring-security-crypto + bcprov). `Share` gained `fileKey` (unprotected only) and `lifetime` (chosen expiry, applied on complete). Deviation from plan: `GET /shares/{code}` returns `salt` (the downloader needs it to derive authToken before calling download). `failedAttempts` field is unused (no share lockout by design) — remove it or keep for stats.
7. Cleanup job: `ExpiredObjectCleaner` (`@Scheduled` every 5 min, `SchedulingConfig` off in tests via `dropcode.scheduling.enabled=false`) deletes due R2 objects from `expiry:index` (`expiry-index-remove.lua` removes only if still due).
8. Security (`security/`): `AbuseGuard` + `RateLimiter` (own Redis Lua fixed-window counters instead of Bucket4j — simpler, same Lua approach as the rest), `AbuseProperties` (`dropcode.abuse.*`), Turnstile (`X-Turnstile-Token` header, `CloudflareTurnstileVerifier`, default = Cloudflare test secret, logs a warning), `ClientIpResolver` (`CF-Connecting-IP` only if `TRUST_CLOUDFLARE_HEADER=true`), `SecurityConfig` (stateless, CSRF off, CORS from `CORS_ALLOWED_ORIGINS`, CSP/HSTS/nosniff, deny everything outside `/api/v1/**`, health, `/v3/api-docs`). Errors carry a stable `code` (`common/Problems`): SHARE_NOT_FOUND, INVALID_REQUEST, WRONG_PASSWORD, UPLOAD_NOT_FINISHED, TRY_AGAIN, RATE_LIMITED (+`retryAfterSeconds`), CAPTCHA_REQUIRED.
9. `backend/Dockerfile` (multi-stage, layered jar, non-root, MaxRAMPercentage=75) — smoke-tested locally with `-m 512m` (~300 MB used). `.github/dependabot.yml`.

Notes: Emad wants few pushes — commit locally on a feature branch and push/PR/merge only at big milestones (several roadmap items together) or when asked. `gh` CLI is not installed — Claude opens/merges PRs via the built-in browser (Emad signed in to GitHub there). In Claude's shell `docker` is not on PATH; use `/Applications/Docker.app/Contents/Resources/bin/docker` (`/usr/local/bin/docker` points to OrbStack).

## Roadmap after storage

1. **ShareRepository (Redis)** — key `share:{code}` (Hash): `objectKey`, `size`, `status` (PENDING|READY), `remaining`, `protected`, `authHash`, `salt`, `encMeta`, `failedAttempts`, `expiresAt`. TTL = expiry (15 min while PENDING). Create with `SET NX`/`HSETNX` semantics to avoid code collisions (retry on clash). `expiry:index` sorted set (score = expiresAt) for R2 cleanup. Object keys `objects/{uuid}` (never contain the code or filename).
2. **Lua script** for atomic download decrement (`remaining > 0` check + decrement in one op); concurrency test with 100 parallel requests.
3. **ShareService + ShareController** — `/api/v1`:
   - `POST /shares` → validate size ≤ 30MB, options; create PENDING; return code + presigned PUT (10 min) with signed Content-Length.
   - `POST /shares/{code}/complete` → `HeadObject` size check → READY, final TTL.
   - `GET /shares/{code}` → protected?, size, expiresAt. Unknown/expired = same generic 404.
   - `POST /shares/{code}/download` → verify authToken (if protected), atomic decrement, presigned GET (60s) + `encMeta` + `salt`. When remaining hits 0: delete key now, delete R2 object ~2 min later.
   - Errors as `ProblemDetail` (RFC 9457).
   - Options: downloads 1/3/5/10 (default 1); expiry 10m/1h/24h/7d (default 24h).
4. **Cleanup job** — `@Scheduled` every 5 min reading `expiry:index`; plus R2 lifecycle rule deleting objects older than 8 days.
5. **Security** — Bucket4j limits (lookup 10/min & 50/h per IP; upload 10/h; wrong passwords limited **per IP** (e.g. 5/15 min) — **never lock or delete the share** on wrong passwords: one recipient typing it wrong must not block everyone else it was shared with (Emad's decision); Turnstile after 3 bad codes). IP from `CF-Connecting-IP`. Spring Security (Argon2 from spring-security-crypto), CORS frontend-only, HSTS/CSP/nosniff, constant-time compare, Dependabot.
   - Encryption (browser, Web Crypto): protected files → PBKDF2-SHA256 600k iters + random salt → 64 bytes: first 32 = AES-256-GCM key (never leaves device), last 32 = `authToken` sent to server, stored as Argon2id hash. Unprotected → random key stored in Redis (not E2E; UI says so). Filename encrypted too (`encMeta`).
6. **Frontend** — Vite + React + TS + Tailwind; upload page (drop zone, advanced options collapsed, progress, code + copy + link + QR), download page (5 auto-advancing boxes, `/{code}` deep link), i18n ar/en, PWA + Android Share Target, OpenAPI-generated types (springdoc-openapi).
7. **Deploy (Railway)** — backend from `backend/Dockerfile`, Railway Redis on private network, wait-for-CI, healthcheck `/actuator/health`, custom domain `api.<domain>` via Cloudflare, usage limit ~$15. R2 bucket CORS: allow PUT/GET from frontend origin only. Frontend on Cloudflare Pages.
8. **Polish** — Playwright E2E, README with live link + GIF + architecture diagram, ADRs in `docs/adr/`, SonarCloud, JaCoCo.

Testing stack: JUnit 5 + AssertJ + Mockito, Testcontainers (Redis), MockMvc, Vitest, Playwright.

Full plan document: https://claude.ai/artifact/B3YDNVZdrUMUghWx91MqEL
