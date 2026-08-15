package com.nspawnmgr.web.dto;

import java.time.Instant;

public record PendingContainerResponse(Long id, String name, String ownerUsername, String templateName,
                                         Instant requestedAt) {
}
