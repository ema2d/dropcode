package com.dropcode.backend.share;

public enum ShareStatus {
    /** Code reserved, browser is still uploading to R2. */
    PENDING,
    /** Upload verified with HeadObject, downloadable. */
    READY
}
