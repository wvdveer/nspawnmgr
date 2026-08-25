package com.nspawnmgr.web.dto;

public record PackageDownloadStatusResponse(
        String downloadId,
        String state,
        long bytesDownloaded,
        Long totalBytes,
        String errorMessage,
        Long cachedPackageId
) {
}
