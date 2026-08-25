package com.nspawnmgr.domain;

/** Shared three-state value for {@link Template#getSshState()}/{@link Template#getRdpState()}/
 *  {@link Template#getVncState()} - replaces the earlier separate booleans (sshPreinstalled,
 *  rdpCapable, vncCapable), which couldn't express "already installed" for RDP/VNC or "cannot be
 *  installed at all" for SSH. */
public enum TemplateFeatureState {
    /** The template's own image already has this installed and enabled - provisioning skips the
     *  redundant download/install/enable step for a container cloned from it. */
    PREINSTALLED,
    /** Not yet installed, but nspawnmgr knows how to install it (via the template's package
     *  manager) when a container is created from this template. */
    CAPABLE,
    /** Cannot be installed via this template at all - disables the corresponding option on the
     *  New container form. */
    NOT_CAPABLE;

    /** Lowercase, space-separated form used in the Templates admin UI (list + edit form). */
    public String label() {
        return switch (this) {
            case PREINSTALLED -> "preinstalled";
            case CAPABLE -> "capable";
            case NOT_CAPABLE -> "not capable";
        };
    }
}
