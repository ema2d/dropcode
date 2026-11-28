package com.dropcode.backend.share;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.dropcode.backend.share.ShareDtos.CompleteShareResponse;
import com.dropcode.backend.share.ShareDtos.CreateShareRequest;
import com.dropcode.backend.share.ShareDtos.CreateShareResponse;
import com.dropcode.backend.share.ShareDtos.DownloadRequest;
import com.dropcode.backend.share.ShareDtos.DownloadResponse;
import com.dropcode.backend.share.ShareDtos.ShareInfoResponse;
import com.dropcode.backend.share.ShareDtos.UploadTarget;
import com.dropcode.backend.storage.PresignedUpload;
import com.dropcode.backend.storage.StorageService;

/**
 * Share lifecycle: create (PENDING + presigned PUT) → complete (verify upload, READY)
 * → info → download (atomic decrement + presigned GET). File bytes never pass through here.
 */
@Service
class ShareService {

    static final Set<Integer> ALLOWED_DOWNLOADS = Set.of(1, 3, 5, 10);
    static final Duration PENDING_TTL = Duration.ofMinutes(15);
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);
    static final Duration DOWNLOAD_URL_TTL = Duration.ofSeconds(60);
    /** After the last download the R2 object lives this long, so the GET just handed out can finish. */
    static final Duration OBJECT_DELETE_GRACE = Duration.ofMinutes(2);
    static final int MAX_CODE_ATTEMPTS = 5;

    private final ShareRepository repository;
    private final StorageService storage;
    private final CodeGenerator codes;
    private final PasswordEncoder authTokenEncoder;
    private final Clock clock;

    ShareService(ShareRepository repository, StorageService storage, CodeGenerator codes,
            PasswordEncoder authTokenEncoder, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.codes = codes;
        this.authTokenEncoder = authTokenEncoder;
        this.clock = clock;
    }

    CreateShareResponse create(CreateShareRequest request) {
        int downloads = request.downloads() == null ? 1 : request.downloads();
        if (!ALLOWED_DOWNLOADS.contains(downloads)) {
            throw ShareErrors.badRequest("downloads must be one of 1, 3, 5, 10");
        }
        ShareExpiry expiry = ShareExpiry.fromLabel(request.expiry());
        String authHash = validateKeys(request);

        String objectKey = "objects/" + UUID.randomUUID();
        Instant uploadDeadline = now().plus(PENDING_TTL);

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codes.generate();
            Share share = new Share(code, objectKey, request.size(), ShareStatus.PENDING, downloads,
                    request.passwordProtected(), authHash, request.salt(), request.encMeta(),
                    request.fileKey(), 0, expiry.duration(), uploadDeadline);

            if (repository.createIfAbsent(share, PENDING_TTL)) {
                PresignedUpload upload = storage.presignUpload(objectKey, request.size(), UPLOAD_URL_TTL);
                return new CreateShareResponse(code, new UploadTarget(upload.url(), upload.headers(),
                        now().plus(UPLOAD_URL_TTL)));
            }
        }
        // ~28.6M codes: five collisions in a row means the code space is nearly full
        throw ShareErrors.unavailable("Could not allocate a share code. Please try again.");
    }

    CompleteShareResponse complete(String rawCode) {
        String code = parse(rawCode);
        Share share = repository.find(code).orElseThrow(ShareErrors::notFound);

        if (share.status() == ShareStatus.READY) {
            // Idempotent: the uploader may retry after a network error
            return new CompleteShareResponse(code, share.expiresAt(), share.remaining());
        }

        long uploaded = storage.objectSize(share.objectKey())
                .orElseThrow(() -> ShareErrors.conflict("The file has not been uploaded yet."));
        if (uploaded != share.size()) {
            storage.delete(share.objectKey());
            repository.delete(code);
            throw ShareErrors.badRequest("Uploaded file size does not match the declared size.");
        }

        Instant expiresAt = now().plus(share.lifetime());
        if (!repository.markReady(code, expiresAt)) {
            throw ShareErrors.notFound();
        }
        return new CompleteShareResponse(code, expiresAt, share.remaining());
    }

    ShareInfoResponse info(String rawCode) {
        Share share = findReady(rawCode);
        return new ShareInfoResponse(share.passwordProtected(), share.size(), share.expiresAt(), share.salt());
    }

    DownloadResponse download(String rawCode, DownloadRequest request) {
        Share share = findReady(rawCode);

        if (share.passwordProtected()) {
            String token = request == null ? null : request.authToken();
            if (token == null || !authTokenEncoder.matches(token, share.authHash())) {
                throw ShareErrors.unauthorized();
            }
        }

        OptionalInt left = repository.consumeDownload(share.code(), now().plus(OBJECT_DELETE_GRACE));
        if (left.isEmpty()) {
            // Someone else took the last download between find() and here
            throw ShareErrors.notFound();
        }

        URI url = storage.presignDownload(share.objectKey(), DOWNLOAD_URL_TTL);
        return new DownloadResponse(url, share.encMeta(), share.fileKey(), left.getAsInt());
    }

    /** @return Argon2id hash of the authToken for protected shares, else null */
    private String validateKeys(CreateShareRequest request) {
        if (request.passwordProtected()) {
            if (isBlank(request.authToken()) || isBlank(request.salt())) {
                throw ShareErrors.badRequest("Protected shares need authToken and salt.");
            }
            if (request.fileKey() != null) {
                // The key of a protected share must never reach the server
                throw ShareErrors.badRequest("Protected shares must not send fileKey.");
            }
            return authTokenEncoder.encode(request.authToken());
        }
        if (isBlank(request.fileKey())) {
            throw ShareErrors.badRequest("Unprotected shares need fileKey.");
        }
        if (request.authToken() != null || request.salt() != null) {
            throw ShareErrors.badRequest("Unprotected shares must not send authToken or salt.");
        }
        return null;
    }

    private Share findReady(String rawCode) {
        return repository.find(parse(rawCode))
                .filter(s -> s.status() == ShareStatus.READY)
                .orElseThrow(ShareErrors::notFound);
    }

    private String parse(String rawCode) {
        String code = codes.normalize(rawCode);
        if (!codes.isValid(code)) {
            throw ShareErrors.notFound();
        }
        return code;
    }

    /** Redis stores epoch millis, so keep Instants at millisecond precision. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
