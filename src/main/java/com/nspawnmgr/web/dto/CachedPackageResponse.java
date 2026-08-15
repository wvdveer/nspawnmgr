package com.nspawnmgr.web.dto;

import java.time.Instant;

public record CachedPackageResponse(Long id, String packageManager, String originalFilename, String description,
                                     String uploadedByUsername, long sizeBytes, Instant createdAt) {
}
