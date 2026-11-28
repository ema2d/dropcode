package com.dropcode.backend.share;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dropcode.backend.share.ShareDtos.CompleteShareResponse;
import com.dropcode.backend.share.ShareDtos.CreateShareRequest;
import com.dropcode.backend.share.ShareDtos.CreateShareResponse;
import com.dropcode.backend.share.ShareDtos.DownloadRequest;
import com.dropcode.backend.share.ShareDtos.DownloadResponse;
import com.dropcode.backend.share.ShareDtos.ShareInfoResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shares")
class ShareController {

    private final ShareService service;

    ShareController(ShareService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreateShareResponse create(@Valid @RequestBody CreateShareRequest request) {
        return service.create(request);
    }

    @PostMapping("/{code}/complete")
    CompleteShareResponse complete(@PathVariable String code) {
        return service.complete(code);
    }

    @GetMapping("/{code}")
    ShareInfoResponse info(@PathVariable String code) {
        return service.info(code);
    }

    @PostMapping("/{code}/download")
    DownloadResponse download(@PathVariable String code,
            @Valid @RequestBody(required = false) DownloadRequest request) {
        return service.download(code, request);
    }
}
