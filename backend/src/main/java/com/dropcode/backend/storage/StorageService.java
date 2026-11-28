package com.dropcode.backend.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public interface StorageService {

    PresignedUpload presignUpload(String objectKey, long sizeBytes, Duration ttl);

    URI presignDownload(String objectKey, Duration ttl);

    Optional<Long> objectSize(String objectKey);

    void delete(String objectKey);
}