package com.dropcode.backend.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.ErrorResponseException;

import com.dropcode.backend.share.ShareDtos.CreateShareRequest;
import com.dropcode.backend.share.ShareDtos.DownloadRequest;
import com.dropcode.backend.storage.PresignedUpload;
import com.dropcode.backend.storage.StorageService;

/** Fast unit tests: everything around ShareService is mocked, time is frozen. */
class ShareServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T12:00:00Z");

    private final ShareRepository repository = mock(ShareRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final CodeGenerator codes = mock(CodeGenerator.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private ShareService service;

    @BeforeEach
    void setUp() {
        service = new ShareService(repository, storage, codes, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
        when(codes.generate()).thenReturn("AAAAA", "BBBBB", "CCCCC", "DDDDD", "EEEEE", "FFFFF");
        when(codes.normalize(any())).thenAnswer(i -> new CodeGenerator().normalize(i.getArgument(0)));
        when(codes.isValid(any())).thenAnswer(i -> new CodeGenerator().isValid(i.getArgument(0)));
        when(storage.presignUpload(any(), anyLong(), any()))
                .thenReturn(new PresignedUpload(URI.create("https://r2/put"), Map.of()));
        when(storage.presignDownload(any(), any())).thenReturn(URI.create("https://r2/get"));
        when(encoder.encode("token")).thenReturn("argon-hash");
    }

    // ---- create ----

    @Test
    void create_storesPendingShareWithDefaults() {
        when(repository.createIfAbsent(any(), any())).thenReturn(true);

        var response = service.create(unprotected(1024, null, null));

        ArgumentCaptor<Share> saved = ArgumentCaptor.forClass(Share.class);
        verify(repository).createIfAbsent(saved.capture(), eq(ShareService.PENDING_TTL));
        Share share = saved.getValue();
        assertThat(response.code()).isEqualTo("AAAAA");
        assertThat(share.status()).isEqualTo(ShareStatus.PENDING);
        assertThat(share.remaining()).isEqualTo(1);
        assertThat(share.lifetime()).isEqualTo(Duration.ofHours(24));
        assertThat(share.expiresAt()).isEqualTo(NOW.plus(ShareService.PENDING_TTL));
        assertThat(share.objectKey()).startsWith("objects/").doesNotContain("AAAAA");
        verify(storage).presignUpload(share.objectKey(), 1024, ShareService.UPLOAD_URL_TTL);
    }

    @Test
    void create_retriesWithNewCodeOnCollision() {
        when(repository.createIfAbsent(any(), any())).thenReturn(false, false, true);

        var response = service.create(unprotected(10, 3, "7d"));

        assertThat(response.code()).isEqualTo("CCCCC");
        verify(repository, times(3)).createIfAbsent(any(), any());
    }

    @Test
    void create_givesUpAfterMaxAttempts() {
        when(repository.createIfAbsent(any(), any())).thenReturn(false);

        assertStatus(() -> service.create(unprotected(10, null, null)), HttpStatus.SERVICE_UNAVAILABLE);
        verify(repository, times(ShareService.MAX_CODE_ATTEMPTS)).createIfAbsent(any(), any());
        verify(storage, never()).presignUpload(any(), anyLong(), any());
    }

    @Test
    void create_rejectsInvalidOptions() {
        assertStatus(() -> service.create(unprotected(10, 2, null)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.create(unprotected(10, null, "2h")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_protectedHashesTokenAndNeverStoresPlainToken() {
        when(repository.createIfAbsent(any(), any())).thenReturn(true);

        service.create(new CreateShareRequest(10, null, null, true, "meta", "token", "salt", null));

        ArgumentCaptor<Share> saved = ArgumentCaptor.forClass(Share.class);
        verify(repository).createIfAbsent(saved.capture(), any());
        assertThat(saved.getValue().authHash()).isEqualTo("argon-hash");
        assertThat(saved.getValue().fileKey()).isNull();
    }

    @Test
    void create_rejectsWrongKeyCombinations() {
        // protected without token / salt
        assertStatus(() -> service.create(new CreateShareRequest(10, null, null, true, "m", null, "salt", null)),
                HttpStatus.BAD_REQUEST);
        // protected but leaking fileKey
        assertStatus(() -> service.create(new CreateShareRequest(10, null, null, true, "m", "token", "salt", "key")),
                HttpStatus.BAD_REQUEST);
        // unprotected without fileKey
        assertStatus(() -> service.create(new CreateShareRequest(10, null, null, false, "m", null, null, null)),
                HttpStatus.BAD_REQUEST);
        verify(repository, never()).createIfAbsent(any(), any());
    }

    // ---- complete ----

    @Test
    void complete_marksReadyWithChosenLifetime() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.PENDING, false)));
        when(storage.objectSize("objects/x")).thenReturn(Optional.of(100L));
        when(repository.markReady(any(), any())).thenReturn(true);

        var response = service.complete(" aaaaa ");

        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        verify(repository).markReady("AAAAA", NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void complete_beforeUpload_isConflict() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.PENDING, false)));
        when(storage.objectSize("objects/x")).thenReturn(Optional.empty());

        assertStatus(() -> service.complete("AAAAA"), HttpStatus.CONFLICT);
        verify(repository, never()).markReady(any(), any());
    }

    @Test
    void complete_sizeMismatch_deletesEverything() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.PENDING, false)));
        when(storage.objectSize("objects/x")).thenReturn(Optional.of(999L));

        assertStatus(() -> service.complete("AAAAA"), HttpStatus.BAD_REQUEST);
        verify(storage).delete("objects/x");
        verify(repository).delete("AAAAA");
    }

    @Test
    void complete_isIdempotentWhenAlreadyReady() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.READY, false)));

        assertThat(service.complete("AAAAA").code()).isEqualTo("AAAAA");
        verify(storage, never()).objectSize(any());
    }

    // ---- info / download ----

    @Test
    void invalidOrPendingCodes_lookExactlyLikeUnknownCodes() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.PENDING, false)));

        assertStatus(() -> service.info("AAAAA"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.info("O0IL1"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.info("ZZZZZ"), HttpStatus.NOT_FOUND);
        verify(repository, never()).find("O0IL1");
    }

    @Test
    void download_unprotected_returnsUrlAndKey() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.READY, false)));
        when(repository.consumeDownload("AAAAA", NOW.plus(ShareService.OBJECT_DELETE_GRACE)))
                .thenReturn(OptionalInt.of(0));

        var response = service.download("AAAAA", null);

        assertThat(response.url()).isEqualTo(URI.create("https://r2/get"));
        assertThat(response.fileKey()).isEqualTo("key");
        assertThat(response.downloadsLeft()).isZero();
        verify(storage).presignDownload("objects/x", ShareService.DOWNLOAD_URL_TTL);
    }

    @Test
    void download_protected_wrongToken_isUnauthorizedAndDoesNotConsume() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.READY, true)));
        when(encoder.matches("bad", "argon-hash")).thenReturn(false);

        assertStatus(() -> service.download("AAAAA", new DownloadRequest("bad")), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> service.download("AAAAA", null), HttpStatus.UNAUTHORIZED);
        verify(repository, never()).consumeDownload(any(), any());
    }

    @Test
    void download_lostRace_isNotFound() {
        when(repository.find("AAAAA")).thenReturn(Optional.of(share(ShareStatus.READY, false)));
        when(repository.consumeDownload(any(), any())).thenReturn(OptionalInt.empty());

        assertStatus(() -> service.download("AAAAA", null), HttpStatus.NOT_FOUND);
        verify(storage, never()).presignDownload(any(), any());
    }

    private static CreateShareRequest unprotected(long size, Integer downloads, String expiry) {
        return new CreateShareRequest(size, downloads, expiry, false, "meta", null, null, "key");
    }

    private static Share share(ShareStatus status, boolean protectedShare) {
        return new Share("AAAAA", "objects/x", 100, status, 1, protectedShare,
                protectedShare ? "argon-hash" : null, protectedShare ? "salt" : null, "meta",
                protectedShare ? null : "key", 0, Duration.ofHours(1), NOW.plus(Duration.ofMinutes(15)));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
