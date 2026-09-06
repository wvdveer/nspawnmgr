package com.nspawnmgr.domain;

/**
 * Desktop environment to install alongside RDP/VNC - NONE_X11 matches the original RDP-only,
 * X11-session behavior. Each real desktop manager (KDE_STANDARD, XFCE4) has both an X11 and a
 * Wayland variant; NONE also splits in two, since Wayland has no display-server/window-manager
 * split the way X11 does - some compositor always has to run, so "no desktop" still means picking
 * a minimal one (see NONE_WAYLAND's own provisioning, which installs a chrome-free compositor
 * rather than nothing at all the way NONE_X11 does).
 *
 * <p>RDP (xrdp/xorgxrdp) has no Wayland story in this codebase - it stays available only for the
 * *_X11 variants; see ProvisioningService's own RDP-provisioning guard and create.js's UI-side
 * disable of the Wayland options once "Enable RDP" is checked.
 *
 * <p>GNOME is deliberately excluded from the selectable UI (descoped from v0.5.0, 2026-09-06) but
 * kept as a real enum constant with real, fully-implemented provisioning code - {@code
 * provisionGnomeRdp} genuinely works mechanically (GDM, the self-signed cert, credentials, and the
 * RDP listener on port 3389 all confirmed working, on both Fedora and Arch), it's the RDP
 * connection itself that's broken. {@code isWayland()} returns {@code false} for it even though
 * current GNOME Shell is Wayland-only, since its provisioning path is neither the *_WAYLAND
 * branches' {@code cage}/{@code sway}+{@code wayvnc} VNC mechanism nor the ordinary X11 RDP path -
 * genuinely RDP-only, via {@code gnome-remote-desktop}'s native "system mode". Headless VNC for
 * GNOME was separately investigated and is a confirmed architectural dead end (not merely
 * unproven): {@code gnome-remote-desktop}'s remote-desktop screencast pipeline requires real
 * DRM/GBM zero-copy buffer sharing for EGL, with no software/CPU fallback (unlike wlroots, which
 * is why {@code cage}+{@code wayvnc} works headlessly), and systemd-nspawn containers have no
 * GPU/DRM device exposure at all.
 *
 * <p><b>History: descoped 2026-09-03 (guacd FreeRDP2-vs-3 mismatch), re-enabled 2026-09-05 once
 * that was fixed, re-descoped 2026-09-06 for a new, deeper, unrelated reason.</b> The original
 * 2026-09-03 blocker was external to this app's own Java code: {@code guacd} was a vendored,
 * self-contained bundle running FreeRDP 2.11.7, while {@code gnome-remote-desktop} hard-depends on
 * FreeRDP 3.x. The {@code freerdp3-guacd-upgrade} branch (merged 2026-09-05) rebuilt guacd's
 * vendored bundle against guacamole-server 1.6.0/FreeRDP 3.5.1 to fix exactly that, and GNOME was
 * re-enabled the same day.
 *
 * <p>Live-verified end-to-end the next day (2026-09-06, fresh containers on both Fedora and Arch)
 * that this did NOT actually fix GNOME: {@code gnome-remote-desktop}'s system-mode RDP NLA
 * authentication rejects every credential identically - a deliberately WRONG password produces
 * the exact same {@code ERRCONNECT_LOGON_FAILURE}/{@code nla_recv_pdu() fail} as the genuinely
 * correct one (confirmed correct via the {@code GetCredentials} D-Bus method, which reads back
 * exactly what {@code grdctl set-credentials} was given). Reproduced identically on Fedora
 * ({@code gnome-remote-desktop} 49.3-2.fc43, FreeRDP 3.31.0) and Arch (50.2-1, FreeRDP 3.31.1) -
 * two different distros, two major GNOME versions, two different FreeRDP point releases, plus a
 * third distinct FreeRDP release (guacd's own 3.5.1) from the original Fedora test - all failing
 * the same way. A client-side FreeRDP bug is unlikely given three different FreeRDP releases all
 * fail identically; this points at something broadly wrong with {@code gnome-remote-desktop}'s own
 * NLA/CredSSP server implementation across its current released version range. Ruled out before
 * concluding this: the disable/enable ordering around {@code set-credentials} (tested both orders,
 * and no restart at all - all fail identically), RDP security mode (explicit NLA is *worse*, a
 * protocol-level negotiation failure; TLS-only is flatly refused,
 * {@code HYBRID_REQUIRED_BY_SERVER} - NLA is mandatory, no fallback), and Arch's newer
 * {@code AuthMethods} property/{@code set-auth-methods} mechanism (explicitly enabling
 * {@code credentials} made no difference). Corroborated by real, currently-open upstream bug
 * reports describing the same general class of failure against real Windows RDP clients too, not
 * just FreeRDP: Ubuntu LP #2141992 and Red Hat Bugzilla #2189376.
 *
 * <p>SWAY is Wayland-only by design (no X11 counterpart the way KDE/Xfce have - i3 is the
 * analogous X11 tiling WM, a different project entirely), so it's a single bare constant like
 * GNOME rather than a *_X11/*_WAYLAND pair. Uses the same {@code cage}+{@code wayvnc} VNC
 * mechanism NONE_WAYLAND does (both wlroots-based) - see ProvisioningService's own
 * waylandVncUnitInstallCommand and its APT-template gate, which applies to SWAY for the identical
 * reason it applies to NONE_WAYLAND (Debian bookworm's wlroots build is too old for either).
 *
 * <p>HYPRLAND is deliberately excluded from the selectable UI (descoped from v0.5.0, 2026-09-04),
 * same treatment as GNOME above - kept as a real enum constant with real provisioning code, not
 * vestigial. Was added 2026-09-03 on the assumption it would reuse the identical
 * {@code wayvnc}-over-VNC mechanism SWAY/NONE_WAYLAND do (both wlroots-based), sidestepping the
 * FreeRDP problem that got GNOME descoped. Live testing (arch-hyprland, yoga, 2026-09-04) found a
 * different, also-external blocker: current Hyprland (confirmed 0.56.2) no longer links wlroots
 * directly - it uses its own Aquamarine backend library (confirmed 0.14.0), which dropped the
 * {@code WLR_BACKENDS=headless} mechanism entirely and has no documented equivalent
 * ({@code AQ_DRM_DEVICES}/{@code AQ_NO_ATOMIC}/{@code AQ_MGPU_NO_EXPLICIT}/{@code AQ_NO_MODIFIERS}
 * only cover real-DRM tuning). With no GPU/DRM device in an nspawn container, Aquamarine's backend
 * creation just fails ({@code CBackend::create() failed!}) regardless of env vars. This is a known,
 * currently-open, unresolved upstream regression - Hyprland issues #7753 ("Headless output no
 * longer working": "This used to work before, iirc prior to the AQ changes") and #7917 ("Headless
 * mode no longer works") - not something provisioning-side config can work around. A pre-Aquamarine
 * Hyprland build (~pre-0.40) would need a manual/AUR build rather than the distro package, mirroring
 * the FreeRDP3-pin idea already rejected for GNOME; revisit if/when upstream restores headless
 * support. {@code isWayland()} returns {@code false} for it for the same reason it does for GNOME -
 * its real (attempted) provisioning path isn't reachable, so it's not meaningfully in the
 * X11/Wayland split this enum otherwise models.
 *
 * <p>SWAY remains unaffected: it still links a distro-provided libwlroots directly (not Aquamarine,
 * which is Hyprland-specific), so {@code WLR_BACKENDS=headless} continues to work for it exactly as
 * it does for NONE_WAYLAND.
 *
 * <p>WAYFIRE, added 2026-09-06 as a fuller-desktop-feeling alternative once GNOME was re-descoped
 * (floating windows + a Compiz-style effects/plugin system, unlike SWAY's tiling-only model) -
 * same shape as SWAY (Wayland-only, no X11 counterpart, reuses the identical {@code cage}/
 * {@code sway}-style {@code wayvnc}-over-headless-VNC mechanism). Confirmed (catalog-level, not
 * yet live) that it directly links a distro's own {@code libwlroots} package the same way SWAY
 * does (not a private fork like Hyprland's Aquamarine) - packages.debian.org confirms Debian
 * bookworm's {@code wayfire} (0.7.4-3+deb12u1) depends on {@code libwlroots10 (>= 0.15.0-5)}, the
 * exact same too-old-for-wayvnc's-virtual-pointer-protocol version that already gates
 * NONE_WAYLAND/SWAY off APT templates - folded into the identical
 * {@code NEEDS_RECENT_WLROOTS_FOR_INPUT} gate rather than treated as a new problem. Real package
 * (not AUR-only) confirmed on Fedora (`dnf install wayfire`) and Arch (`extra` repo,
 * {@code pacman -S wayfire}) too. Not yet live-verified whether Wayfire's own startup path honors
 * {@code WLR_BACKENDS=headless} the same automatic way SWAY does (wlroots itself handles that env
 * var during backend autocreation, not compositor-specific code, so this is expected to work, but
 * Hyprland's own Aquamarine surprise is exactly why this needs a real live test before trusting it
 * the way SWAY is trusted).
 */
public enum DesktopManager {
    NONE_X11, NONE_WAYLAND, GNOME, KDE_STANDARD_X11, KDE_STANDARD_WAYLAND, XFCE4_X11, XFCE4_WAYLAND, SWAY, HYPRLAND, WAYFIRE;

    public boolean isWayland() {
        return this == NONE_WAYLAND || this == KDE_STANDARD_WAYLAND || this == XFCE4_WAYLAND || this == SWAY || this == WAYFIRE;
    }

    public boolean isNone() {
        return this == NONE_X11 || this == NONE_WAYLAND;
    }
}
