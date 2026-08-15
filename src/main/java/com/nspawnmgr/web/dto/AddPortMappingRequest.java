package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.PortMappingProtocol;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public record AddPortMappingRequest(
        @Min(1) @Max(65535) int hostPort,
        @Min(1) @Max(65535) int containerPort,
        @NotNull PortMappingProtocol protocol
) {
}
