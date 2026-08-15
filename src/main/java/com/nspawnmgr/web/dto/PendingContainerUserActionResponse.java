package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.ContainerUserActionType;

import java.time.Instant;

public record PendingContainerUserActionResponse(Long id, Long containerId, String containerName,
                                                   boolean containerRunning, String requestedByUsername,
                                                   ContainerUserActionType actionType, String username,
                                                   Instant requestedAt) {
}
