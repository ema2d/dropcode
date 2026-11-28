package com.dropcode.backend.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
class R2StorageService implements StorageService {

    private static final Set<String> BROWSER_MANAGED_HEADERS = Set.of("host", "content-length");

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    R2StorageService(S3Client client, S3Presigner presigner, StorageProperties props) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = props.bucket();
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, long sizeBytes, Duration ttl) {
        var presigned = presigner.presignPutObject(r -> r
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(objectKey).contentLength(sizeBytes)));

        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(e -> !BROWSER_MANAGED_HEADERS.contains(e.getKey().toLowerCase()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));

        return new PresignedUpload(URI.create(presigned.url().toString()), headers);
    }

    @Override
    public URI presignDownload(String objectKey, Duration ttl) {
        var presigned = presigner.presignGetObject(r -> r
                .signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(bucket).key(objectKey)));
        return URI.create(presigned.url().toString());
    }

    @Override
    public Optional<Long> objectSize(String objectKey) {
        try {
            return Optional.of(client.headObject(r -> r.bucket(bucket).key(objectKey)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(r -> r.bucket(bucket).key(objectKey));
    }
}