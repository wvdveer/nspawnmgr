package com.nspawnmgr.domain;

public enum CredentialType {
    SSH_KEY, RDP_PASSWORD, VNC_PASSWORD,
    /** The RDP/NLA-layer shared secret {@code gnome-remote-desktop}'s system mode gates on for
     *  {@link DesktopManager#GNOME} - a synthetic identity, NOT any real Linux account's password
     *  (that's still stored as the ordinary {@link #RDP_PASSWORD}, used for the real interactive
     *  GDM login that happens behind this gate - see ProvisioningService's own
     *  {@code provisionGnomeRdp}). Column is {@code VARCHAR(20)}, no CHECK constraint - fits with
     *  room to spare, no migration needed to add this constant. */
    RDP_NLA_SECRET
}
