package com.nspawnmgr.domain;

/**
 * A container's {@code PrivateUsers=} setting in its generated {@code .nspawn} file - controls
 * whether/how systemd-nspawn puts the container in its own user namespace. Leaving a template's
 * {@code privateUsersMode} unset (the default) lets its containers silently follow whatever the
 * host's own systemd-nspawn defaults to ({@code pick} on current systemd), which idmap-mounts the
 * container's root filesystem with container UID 0 mapped to an arbitrary, non-zero host UID.
 * Confirmed live on yoga/fed1: that shift broke unix_chkpwd's {@code /etc/shadow} read with a plain
 * EACCES even though it ran as genuine root with CAP_DAC_OVERRIDE/CAP_DAC_READ_SEARCH present (the
 * kernel's capability-vs-inode-ownership check doesn't survive the idmap translation for that case) -
 * {@code fedora-minimal} is explicitly set to {@link #IDENTITY} for this reason. Other templates
 * stay on the host default unless/until they need it too - see
 * TemplateService#minimalFlavorPrivateUsersMode and NspawnSettingsRenderer.
 */
public enum PrivateUsersMode {

    /** Real, separate user namespace per container, but container UID 0 maps to host UID 0 (no
     *  shift) - avoids the idmap/capability interaction that broke shadow-file reads under `pick`. */
    IDENTITY("identity"),
    /** systemd-nspawn's own upstream default - a private namespace with an arbitrary, picked
     *  non-zero UID range. Known to break PAM helpers that shell out to unix_chkpwd; only meant for
     *  a template where that's been separately worked around or doesn't apply. */
    PICK("pick"),
    /** No private user namespace at all - container root is genuinely host root. Strictly more
     *  permissive than IDENTITY; PICK's own namespace-isolation benefit already covers what most
     *  admins want, so this is only for a template with some other reason to need it. */
    NO("no");

    private final String nspawnValue;

    PrivateUsersMode(String nspawnValue) {
        this.nspawnValue = nspawnValue;
    }

    /** The literal value systemd-nspawn's {@code PrivateUsers=} setting expects. */
    public String nspawnValue() {
        return nspawnValue;
    }
}
