package com.nspawnmgr.domain;

import java.util.Set;

/**
 * The fixed set of PAM service names {@link ContainerPamService} may name — never free-form,
 * since a service name is interpolated directly into a container-side path
 * ({@code /etc/pam.d/<service>}) by PamCredentialAuthService.
 */
public final class PamServiceCatalog {

    public static final String XRDP_SESMAN = "xrdp-sesman";
    public static final String SSHD = "sshd";
    public static final String LOGIN = "login";
    public static final String SUDO = "sudo";
    public static final String SU = "su";

    public static final Set<String> KNOWN_SERVICES = Set.of(XRDP_SESMAN, SSHD, LOGIN, SUDO, SU);

    private PamServiceCatalog() {
    }
}
