package com.dropcode.backend.share;

import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dropcode.backend.security.AbuseGuard;
import com.dropcode.backend.security.ClientIpResolver;
import com.dropcode.backend.share.ShareDtos.CompleteShareResponse;
import com.dropcode.backend.share.ShareDtos.CreateShareRequest;
import com.dropcode.backend.share.ShareDtos.CreateShareResponse;
import com.dropcode.backend.share.ShareDtos.DownloadRequest;
import com.dropcode.backend.share.ShareDtos.DownloadResponse;
import com.dropcode.backend.share.ShareDtos.ShareInfoResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shares")
class ShareController {

    static final String TURNSTILE_HEADER = "X-Turnstile-Token";

    private final ShareService service;
    private final AbuseGuard guard;
    private final ClientIpResolver ips;

    ShareController(ShareService service, AbuseGuard guard, ClientIpResolver ips) {
        this.service = service;
        this.guard = guard;
        this.ips = ips;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreateShareResponse create(@Valid @RequestBody CreateShareRequest request, HttpServletRequest http) {
        guard.checkCreate(ips.resolve(http));
        return service.create(request);
    }

    @PostMapping("/{code}/complete")
    CompleteShareResponse complete(@PathVariable String code,
            @RequestHeader(name = TURNSTILE_HEADER, required = false) String turnstile, HttpServletRequest http) {
        String ip = guardLookup(http, turnstile);
        return recordingFailures(ip, () -> service.complete(code));
    }

    @GetMapping("/{code}")
    ShareInfoResponse info(@PathVariable String code,
            @RequestHeader(name = TURNSTILE_HEADER, required = false) String turnstile, HttpServletRequest http) {
        String ip = guardLookup(http, turnstile);
        return recordingFailures(ip, () -> service.info(code));
    }

    @PostMapping("/{code}/download")
    DownloadResponse download(@PathVariable String code,
            @Valid @RequestBody(required = false) DownloadRequest request,
            @RequestHeader(name = TURNSTILE_HEADER, required = false) String turnstile, HttpServletRequest http) {
        String ip = guardLookup(http, turnstile);
        guard.checkPasswordAttempts(ip);
        return recordingFailures(ip, () -> service.download(code, request));
    }

    private String guardLookup(HttpServletRequest http, String turnstile) {
        String ip = ips.resolve(http);
        guard.checkLookup(ip);
        guard.checkCaptcha(ip, turnstile);
        return ip;
    }

    /** Unknown codes count towards the captcha, wrong passwords towards the password limit. */
    private <T> T recordingFailures(String ip, Supplier<T> call) {
        try {
            return call.get();
        } catch (ErrorResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                guard.recordBadCode(ip);
            } else if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                guard.recordWrongPassword(ip);
            }
            throw e;
        }
    }
}
