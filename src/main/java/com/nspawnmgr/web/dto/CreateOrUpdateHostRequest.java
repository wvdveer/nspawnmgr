package com.nspawnmgr.web.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

public record CreateOrUpdateHostRequest(
        @NotBlank String name,
        @NotBlank String hostname,
        @NotBlank String ownerUsername,
        boolean sshEnabled,
        @Min(1) @Max(65535) int sshPort,
        boolean rdpEnabled,
        @Min(1) @Max(65535) int rdpPort,
        boolean vncEnabled,
        @Min(1) @Max(65535) int vncPort
) {
}
