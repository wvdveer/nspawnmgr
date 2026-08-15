package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.ContainerState;

public record ContainerStatusResponse(ContainerState state, String errorMessage) {
}
