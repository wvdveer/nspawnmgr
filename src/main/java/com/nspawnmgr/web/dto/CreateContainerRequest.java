package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.nspawnmgr.domain.KeystrokeButtonPanelPlacement;

public record CreateContainerRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "{validation.name.lowercaseAlphanumericHyphens}") String name,
        @NotNull Long templateId,
        boolean rdpEnabled,
        boolean vncEnabled,
        /** SYSTEMD_NSPAWN only (harmlessly ignored for PODMAN/QEMU) - see
         *  Container#isVpnCapable's own javadoc. Creation-time only, no post-creation edit UI. */
        boolean vpnCapable,
        String desktopManager,
        @Size(max = 500) String description,
        /** PODMAN only - like a Dockerfile CMD, see Container#getPodCommand's own javadoc. Ignored
         *  for SYSTEMD_NSPAWN. */
        @Size(max = 1000) String command,
        /** PODMAN only - see Container#isPodSystemdEnabled's own javadoc. Ignored for
         *  SYSTEMD_NSPAWN. */
        boolean systemdEnabled,
        /** Settable at create time the same way CreateOrUpdateHostRequest already lets an admin set
         *  it while creating a Host - previously a nspawn container could only get this from its own
         *  owner editing it after creation, on containers/detail.html. */
        @NotNull KeystrokeButtonPanelPlacement keystrokeButtonPanelPlacement,
        /** Deliberately a plain primitive with no validation annotation, unlike
         *  keystrokeButtonPanelPlacement above - see
         *  feedback_dto_notnull_field_breaks_hardcoded_json_scripts: a @NotNull field here breaks
         *  every hardcoded JSON fixture that predates it. Omitted entirely just means "off", which
         *  is also this field's own secure-by-default value - see
         *  Container.clipboardCopyLoggingEnabled's own javadoc. */
        boolean clipboardCopyLoggingEnabled
) {
}
