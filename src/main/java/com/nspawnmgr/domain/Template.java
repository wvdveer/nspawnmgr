package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "templates")
@Getter
@Setter
@NoArgsConstructor
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /**
     * Bare identifier for this template's backing file (no extension) — resolved as
     * {@code <templates-dir>/<backend.templateSubdirectory()>/<sourcePath>.tar.gz}, a
     * machinectl import-tar-compatible gzipped tar of the template's root filesystem.
     */
    @Column(name = "source_path", nullable = false)
    private String sourcePath;

    /** Which container/VM technology this template boots under - see ContainerBackend's own javadoc
     *  for what's actually usable today versus just creatable/manageable. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContainerBackend backend = ContainerBackend.SYSTEMD_NSPAWN;

    /** Nullable (unlike every other required field here): a podman image - especially a freshly
     *  "New Pod"-pulled arbitrary one - has no meaningful nspawnmgr-managed package manager concept
     *  until real podman provisioning exists. Matches Container.packageManager's own already-
     *  nullable "not applicable" pattern. */
    @Enumerated(EnumType.STRING)
    @Column(name = "package_manager")
    private PackageManager packageManager;

    @Column(name = "install_ssh_command")
    private String installSshCommand;

    /** Comma-separated package-name override for SSH's own host-side pre-fetch step (see
     *  TemplateService#sshPackagesToPreDownload) - blank/null falls back to the package-manager
     *  default. Lets a distro package rename (e.g. Fedora's tigervnc-server -> tigervnc-x11-server)
     *  be fixed via configuration instead of a Java code change. */
    @Column(name = "ssh_pre_download_packages")
    private String sshPreDownloadPackages;

    /**
     * PREINSTALLED when this template's own backing image already has openssh-server/openssh
     * installed and enabled (see e.g. nspawnmgr-create-debian-template.sh's own "systemctl enable
     * ssh" step) — lets ProvisioningService.provisionSsh skip the redundant host-side download +
     * in-container install + enable steps for a container cloned from it, since they'd be a no-op
     * anyway. Defaults to CAPABLE for a hand-created template (source image unknown, safest
     * assumption); the three official "Set up X-minimal" templates always set this PREINSTALLED,
     * since all three bake scripts do this. NOT_CAPABLE is for a template with no way to install SSH
     * at all (e.g. no package manager - see {@link #packageManager}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ssh_state", nullable = false)
    private TemplateFeatureState sshState = TemplateFeatureState.CAPABLE;

    @Column(name = "install_xrdp_command")
    private String installXrdpCommand;

    /** As {@link #sshPreDownloadPackages}, for XRDP's own pre-fetch step. */
    @Column(name = "xrdp_pre_download_packages")
    private String xrdpPreDownloadPackages;

    /** As {@link #sshState}, for RDP. NOT_CAPABLE is what actually disables the "Enable RDP" option
     *  on the New container form (see e.g. arch-minimal, where xrdp/xorgxrdp were dropped from
     *  Arch's official repos entirely). */
    @Enumerated(EnumType.STRING)
    @Column(name = "rdp_state", nullable = false)
    private TemplateFeatureState rdpState = TemplateFeatureState.CAPABLE;

    @Column(name = "install_vnc_command")
    private String installVncCommand;

    /** As {@link #sshPreDownloadPackages}, for VNC's own pre-fetch step - the field this whole
     *  mechanism exists for (Fedora's tigervnc-server -> tigervnc-x11-server rename). */
    @Column(name = "vnc_pre_download_packages")
    private String vncPreDownloadPackages;

    /** As {@link #sshState}, for VNC. */
    @Enumerated(EnumType.STRING)
    @Column(name = "vnc_state", nullable = false)
    private TemplateFeatureState vncState = TemplateFeatureState.CAPABLE;

    /**
     * A shell command template for the VNC xstartup script's own session-launch line - {@code %s}
     * is replaced with the actual desktop-session command (e.g. {@code startxfce4}). Null uses
     * TemplateService's own default, {@code "exec %s"} - TigerVNC's own natural behavior:
     * confirmed live (deb1, 2026-08-13) that TigerVNC 1.12+ kills the whole Xvnc server the
     * instant the xstartup script's own process exits, which this bare exec triggers directly the
     * moment the user logs out - deliberate, so a real logout actually ends the VNC session and
     * returns the user to Guacamole's own screen, rather than silently redrawing a fresh desktop
     * on the same still-open connection. See ProvisioningService#ensureVncServerRunning for what
     * brings Xvnc back for the next connection attempt instead.
     */
    @Column(name = "vnc_xstartup_template", length = 2000)
    private String vncXstartupTemplate;

    /**
     * The VNC server binary's own process-name pattern, used (anchored as
     * {@code (^|/)<pattern> :0 }, matched against a process's full command line) to find and kill a
     * stale instance before starting a fresh one. Null uses TemplateService's own default,
     * {@code "X[a-z]*vnc"} (matches both {@code Xvnc} and {@code Xtigervnc}) - confirmed live
     * (deb1, 2026-08-13) that Debian's tigervnc-standalone-server actually execs a binary literally
     * named {@code Xtigervnc}, not {@code Xvnc}, so a distro/packaging assumption baked into this
     * pattern is exactly the kind of thing that needs to be overridable rather than hardcoded.
     * Always anchored by the code that uses this (never take just this override and drop the
     * anchor) - see ProvisioningService's own comment for why: an unanchored pattern can match the
     * invoking shell's own command line and kill itself instead of the target process, and a
     * bare-start-of-line anchor alone doesn't work either since the real binary is normally invoked
     * by its absolute path (also confirmed live, deb1, 2026-08-13 - the original anchor-only fix
     * silently matched nothing this whole time).
     */
    @Column(name = "vnc_process_name_pattern", length = 200)
    private String vncProcessNamePattern;

    /** Desktop-manager install-command/pre-fetch overrides - one pair per DesktopManager value
     *  (excluding NONE), same override-then-package-manager-default pattern as SSH/XRDP/VNC above.
     *  Desktop manager is chosen per-container, not per-template, so a template can carry an
     *  override for every desktop manager it might be asked to provision. */
    @Column(name = "install_gnome_command")
    private String installGnomeCommand;

    @Column(name = "gnome_pre_download_packages")
    private String gnomePreDownloadPackages;

    @Column(name = "install_kde_standard_command")
    private String installKdeStandardCommand;

    @Column(name = "kde_standard_pre_download_packages")
    private String kdeStandardPreDownloadPackages;

    @Column(name = "install_xfce4_command")
    private String installXfce4Command;

    @Column(name = "xfce4_pre_download_packages")
    private String xfce4PreDownloadPackages;

    /** Null leaves systemd-nspawn's own {@code PrivateUsers=} default (`pick` on current systemd)
     *  untouched for containers made from this template - see NspawnSettingsRenderer. An explicit
     *  override lets a specific template opt into a different user-namespace posture without a code
     *  change; {@code fedora-minimal} is set to IDENTITY (confirmed live: `pick`'s idmapped root-fs
     *  mount breaks unix_chkpwd's /etc/shadow read there). */
    @Enumerated(EnumType.STRING)
    @Column(name = "private_users_mode")
    private PrivateUsersMode privateUsersMode;

    @Column(nullable = false)
    private boolean active = true;
}
