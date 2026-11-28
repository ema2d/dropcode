package com.dropcode.backend.storage;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        @NotBlank String accountId,
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        @NotBlank String bucket) {

    public URI endpoint() {
        return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    }
}