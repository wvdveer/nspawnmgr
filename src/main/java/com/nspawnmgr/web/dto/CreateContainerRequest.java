package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public record CreateContainerRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "lowercase alphanumeric and hyphens only") String name,
        @NotNull Long templateId,
        boolean rdpEnabled,
        boolean vncEnabled,
        String desktopManager,
        @Size(max = 500) String description,
        /** PODMAN only - like a Dockerfile CMD, see Container#getPodCommand's own javadoc. Ignored
         *  for SYSTEMD_NSPAWN. */
        @Size(max = 1000) String command
) {
}
