package com.nspawnmgr.web.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.nspawnmgr.domain.KeystrokeButtonPanelPlacement;
import com.nspawnmgr.domain.RdpSecurityMode;

public record CreateOrUpdateHostRequest(
        @NotBlank String name,
        @NotBlank String hostname,
        @NotBlank String ownerUsername,
        boolean sshEnabled,
        @Min(1) @Max(65535) int sshPort,
        boolean rdpEnabled,
        @Min(1) @Max(65535) int rdpPort,
        boolean vncEnabled,
        @Min(1) @Max(65535) int vncPort,
        String description,
        @NotNull RdpSecurityMode rdpSecurity,
        @NotNull KeystrokeButtonPanelPlacement keystrokeButtonPanelPlacement
) {
}
