package com.dropcode.backend.storage;

import java.net.URI;
import java.util.Map;

public record PresignedUpload(URI url, Map<String, String> headers) {
}