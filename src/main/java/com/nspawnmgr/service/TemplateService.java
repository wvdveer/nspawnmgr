package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.DesktopManager;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TemplateService {

    // APT entries deliberately don't run "apt-get update" in-container - confirmed live (a real
    // container, "b3", stuck in ERROR with "Command timed out" on exactly this line), apt-get
    // update always hits the network for fresh index data regardless of what's already cached,
    // which is the exact "never touch the network from inside a container" pattern this app
    // otherwise always avoids (see *PackagesToPreDownload below). It's also unnecessary: the
    // host-side pre-download step (ContainerFilesystemProvisioner#downloadPackagesIntoContainer)
    // already ran its own apt-get update against this same rootfs path moments earlier, using the
    // HOST's network - by the time this in-container command runs, the index it reads is already
    // fresh, and every .deb apt would resolve from it is already sitting in the container's own
    // local archive cache. DNF/APK/PACMAN are unaffected (their install commands don't have this
    // problem in the same way) and stay best-effort/unverified, same status they've always had -
    // only APT is the one real template in active use.
    // DEBIAN_FRONTEND=noninteractive here is NOT the same override
    // nspawnmgr-download-packages.sh's own host-side apt-get calls already export - that env var
    // only covers that script's own process, never the separate systemd-run --machine=-targeted
    // exec these commands run under (see ProvisioningService.sh). Confirmed live (a real
    // container, "b4", stuck in ERROR with "Command timed out" on "apt-get install -y xfce4" -
    // zero apt output before the timeout, unlike a genuine fetch failure, with outbound access
    // enabled): without it, any debconf-interactive dependency (keyboard-configuration,
    // console-setup, tzdata, etc. - all pulled in by a desktop metapackage like xfce4) blocks
    // forever waiting on a tty read that systemd-run --pipe --quiet --wait never provides, until
    // ProvisioningService's own 3-minute exec timeout kills it.
    // DNF entries below: "ls .../*.rpm >/dev/null 2>&1 && dnf install -y .../*.rpm || dnf install -y
    // <name>" - confirmed live, dnf5's real package cache lives under
    // /var/cache/libdnf5/<repo-id>-<hash>/, a dynamically-hashed path this app has no way to predict
    // from outside dnf itself. nspawnmgr-download-packages-dnf.sh's own "leave a copy in the
    // container's own dnf cache" step (see that script) writes a flat, non-hashed copy to
    // /var/cache/dnf/ instead - not dnf5's real cache location, so a plain `dnf install -y <name>`
    // never finds it and silently re-fetches the entire dependency closure over the network
    // regardless of what was already pre-fetched. Confirmed live: this is what made a single,
    // unremarkable package ("dnf install -y xrdp" alone) blow PACKAGE_INSTALL_TIMEOUT's 3-minute
    // predecessor - not the package's own size, but a wasted full re-download. Installing the local
    // files directly (when present) uses them with zero network access instead of ever attempting
    // resolution by name; the `ls` guard falls through to the ordinary by-name install when nothing
    // was pre-fetched (e.g. pre-fetch skipped or not eligible for this template).
    // PACMAN entries below: plain `-S` (no `y`) - confirmed live, `-Sy` forces a repo-database
    // network sync from *inside* the container's own network namespace on every single install,
    // the exact "download issued from inside a container is unreliable" pattern that made a real
    // arch2 provisioning run die mid-transfer (see nspawnmgr-download-packages-pacman.sh's own
    // comment). That script already runs its own `-Sy` host-side (via chroot) moments before this
    // install command executes, and already leaves the requested package's own .pkg.tar.* sitting
    // in pacman's real cache dir - so the sync here is not just redundant, it's the one piece of
    // this whole flow still reaching the network from inside the container, undoing the point of
    // fetching everything host-side first. `-S` alone installs from what's already synced/cached
    // with zero network access.
    private static final Map<PackageManager, String> DEFAULT_INSTALL_SSH = Map.of(
            PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server",
            PackageManager.DNF, "ls /var/cache/dnf/*.rpm >/dev/null 2>&1 && dnf install -y /var/cache/dnf/*.rpm || dnf install -y openssh-server",
            PackageManager.APK, "apk add --no-cache openssh",
            PackageManager.PACMAN, "pacman -S --noconfirm openssh"
    );

    private static final Map<PackageManager, String> DEFAULT_INSTALL_XRDP = Map.of(
            PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y xrdp",
            // Confirmed live: EPEL is a RHEL/CentOS-family repo, not applicable to Fedora at all
            // (this project's only real DNF target - see nspawnmgr-create-fedora-template.sh) -
            // `dnf install -y epel-release` fails outright with "No match for argument:
            // epel-release" since no such package exists in Fedora's own repos. xrdp is already
            // available directly from Fedora's default repos, no EPEL needed.
            //
            // .x86_64 pinned: confirmed live, Fedora publishes both xrdp.x86_64 and xrdp.i686 -
            // requesting the bare name lets dnf pull the i686 build's own dependency closure in
            // alongside the x86_64 one, which needs the 32-bit libopenh264.so.8. Fedora's own repo
            // only ships a patent-free "noopenh264" stub for that (the real codec comes from the
            // separate fedora-cisco-openh264 repo, x86_64-only) - the already-installed real 64-bit
            // openh264 and the i686 stub then "obsolete"/"conflict" with each other in both
            // directions, an unresolvable transaction with "conflicting requests". Pinning the arch
            // keeps dnf from ever considering the i686 candidate (or its dependency chain) at all.
            //
            // xorg-x11-server-Xorg + xorgxrdp added 2026-08-14, both .x86_64-pinned the same way -
            // confirmed live these were simply never installed at all (an oversight relative to
            // the PACMAN entry below, which already correctly named xorgxrdp alongside xrdp) -
            // every RDP login failed with "X server could not be started" regardless of PAM or
            // credentials, since there was no X server of any kind for xrdp's default [Xorg]
            // session type to launch. xorgxrdp is xrdp's own purpose-built X.Org driver module
            // (provides the rdpdev/rdpkeyb/rdpmouse drivers a real Xorg needs to run headless
            // inside a container) - confirmed live it needs no dummy/vesa driver workaround, no
            // xorg.conf beyond what the package itself ships, and no bridging through TigerVNC.
            PackageManager.DNF, "ls /var/cache/dnf/*.rpm >/dev/null 2>&1 && dnf install -y /var/cache/dnf/*.rpm || dnf install -y xrdp.x86_64 xorg-x11-server-Xorg.x86_64 xorgxrdp.x86_64",
            PackageManager.APK, "apk add --no-cache xrdp",
            PackageManager.PACMAN, "pacman -S --noconfirm xorgxrdp xrdp"
    );

    private static final Map<PackageManager, String> DEFAULT_INSTALL_VNC = Map.of(
            PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y tigervnc-standalone-server",
            // tigervnc-x11-server, not tigervnc-server - confirmed live on fedora-minimal (fc43):
            // tigervnc-x11-server >= 1.16.2-4.fc43 Obsoletes the older tigervnc-server name outright
            // (a standard RPM package-rename idiom), and is already present as a baseline package on
            // current Fedora, so requesting the old name produces an unresolvable "conflicting
            // requests" transaction against what's already installed.
            PackageManager.DNF, "ls /var/cache/dnf/*.rpm >/dev/null 2>&1 && dnf install -y /var/cache/dnf/*.rpm || dnf install -y tigervnc-x11-server",
            PackageManager.APK, "apk add --no-cache tigervnc",
            // xterm is bundled alongside tigervnc here - Arch has no desktop-manager-independent
            // terminal of its own, and VNC can be (and often is, e.g. arch-minimal) installed with
            // no desktop package at all, leaving no way to open a shell once connected. xterm's
            // dependency footprint (libXaw/libXt only) stays the same regardless of which desktop
            // manager (if any) is also selected, unlike a desktop-tied terminal (konsole/
            // xfce4-terminal) which would pull in that desktop's own framework on every other combo.
            PackageManager.PACMAN, "pacman -S --noconfirm tigervnc xterm"
    );

    /** See Template.vncXstartupTemplate's own comment - a bare exec, TigerVNC's own natural
     *  behavior, so logging out genuinely ends the VNC session. */
    private static final String DEFAULT_VNC_XSTARTUP_TEMPLATE = "exec %s";

    /** See Template.vncProcessNamePattern's own comment - matches both Xvnc and Xtigervnc. The
     *  code that uses this always additionally tolerates a path prefix before it (the real binary
     *  is normally invoked by its absolute path, e.g. "/usr/bin/Xtigervnc :0 ..." - confirmed live,
     *  deb1, 2026-08-13, that anchoring to the bare start of the command line alone never matches
     *  that and silently kills nothing) - this override doesn't need to account for that itself. */
    private static final String DEFAULT_VNC_PROCESS_NAME_PATTERN = "X[a-z]*vnc";

    /** APT and DNF are both exercised for real now (Fedora/Arch templates confirmed live) - APK
     *  entries stay best-effort/unverified, same status DEFAULT_INSTALL_XRDP's APK entry has. */
    private static final Map<DesktopManager, Map<PackageManager, String>> DEFAULT_INSTALL_DESKTOP = Map.of(
            // Confirmed live: Fedora's own `dnf` is dnf5 (the default since Fedora 41), which
            // dropped the legacy one-word `groupinstall` subcommand entirely - it fails with
            // "Unknown argument \"groupinstall\" for command \"dnf5\"". `dnf group install` (two
            // words) is dnf5's own supported syntax for the same operation.
            // "Server with GUI"/"KDE Plasma Workspaces" (RHEL/CentOS-style group names) don't exist
            // on Fedora's own comps at all - confirmed live 2026-08-12, both failed provisioning
            // outright with "No match for argument". Fedora's real groups are `gnome-desktop`
            // ("GNOME") and `kde-desktop` ("KDE") - confirmed live via `dnf group list`/`dnf group
            // info`, gnome-desktop's mandatory packages include gdm/gnome-shell/nautilus, a real
            // installable full desktop.
            DesktopManager.GNOME, Map.of(
                    PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y task-gnome-desktop",
                    PackageManager.DNF, "dnf group install -y gnome-desktop",
                    PackageManager.APK, "apk add --no-cache gnome",
                    PackageManager.PACMAN, "pacman -S --noconfirm gnome"),
            // Confirmed live 2026-08-14: the `kde-desktop` comps group installs Plasma's Wayland
            // session by default but not its X11 one - RDP (this app's only X11-based desktop
            // path besides VNC) failed with "startplasma-x11: not found". Unlike GNOME (which has
            // dropped X11 entirely - see project_gnome_wayland_only_removed.md, GNOME was removed
            // as a selectable option instead of chasing this same fix for it), KDE Plasma still
            // ships a real X11 session as a separate package, `plasma-workspace-x11` ("Xorg
            // support for Plasma", confirmed live via `dnf provides */startplasma-x11`) - named
            // explicitly here, same pattern as XFCE's own missing-package fixes.
            DesktopManager.KDE_STANDARD, Map.of(
                    PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y kde-standard",
                    PackageManager.DNF, "dnf group install -y kde-desktop && dnf install -y plasma-workspace-x11",
                    PackageManager.APK, "apk add --no-cache plasma-desktop",
                    PackageManager.PACMAN, "pacman -S --noconfirm plasma"),
            // "Xfce Desktop" isn't a comps group on current Fedora at all (`dnf group list
            // --available` doesn't list it), so `dnf group install` failed outright with "No match
            // for argument" - that part of the original reasoning here was confirmed live and still
            // holds. But Fedora also has no "xfce4" package/metapackage either (unlike Debian/Arch) -
            // confirmed live, `dnf install xfce4`/`dnf download xfce4`/`dnf provides xfce4` all fail
            // with "No match"/"No package available". An earlier version of this comment claimed
            // "xfce4-session"'s own dependency chain pulls in the rest of a working Xfce session -
            // that's wrong, confirmed live 2026-08-12: a container installed with just xfce4-session
            // gets a session manager with no window manager (xfwm4), no desktop/icons (xfdesktop), and
            // no taskbar (xfce4-panel) - xfce4-session starts, immediately has nothing to manage, and
            // VNC/RDP show a permanent black screen with zero errors anywhere in guacd, Xvnc, or the
            // session logs (spent a long detour suspecting a guacd/TLS regression before finding this
            // - the black screen is what a real Xfce session looks like with those three packages
            // missing). Fixed by naming all four packages explicitly.
            //
            // Same root cause hit a fifth package, confirmed live 2026-08-14: no terminal
            // emulator either - XFCE's own "Open Terminal Here"/right-click menu action failed
            // with "Failed to launch preferred application for category TerminalEmulator -
            // Could not find fallback TerminalEmulator application". Debian's xfce4 metapackage
            // pulls xfce4-terminal in via its own Recommends (apt installs those by default);
            // Fedora has no such metapackage at all here, so nothing pulled one in. Added
            // xfce4-terminal explicitly, same as the other four.
            DesktopManager.XFCE4, Map.of(
                    PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y xfce4",
                    PackageManager.DNF, "ls /var/cache/dnf/*.rpm >/dev/null 2>&1 && dnf install -y /var/cache/dnf/*.rpm || dnf install -y xfce4-session xfwm4 xfdesktop xfce4-panel xfce4-terminal",
                    PackageManager.APK, "apk add --no-cache xfce4",
                    PackageManager.PACMAN, "pacman -S --noconfirm xfce4")
    );

    private final TemplateRepository templateRepository;
    private final ContainerRepository containerRepository;
    private final ContainerFilesystemProvisioner filesystemProvisioner;
    private final SettingsService settingsService;

    public TemplateService(TemplateRepository templateRepository, ContainerRepository containerRepository,
                            ContainerFilesystemProvisioner filesystemProvisioner, SettingsService settingsService) {
        this.templateRepository = templateRepository;
        this.containerRepository = containerRepository;
        this.filesystemProvisioner = filesystemProvisioner;
        this.settingsService = settingsService;
    }

    public List<Template> listActive() {
        return templateRepository.findByActiveTrue();
    }

    public List<Template> listAll() {
        return templateRepository.findAll();
    }

    public Template getById(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such template: " + id));
    }

    public String resolveInstallSshCommand(Template template) {
        if (template.getInstallSshCommand() != null && !template.getInstallSshCommand().isBlank()) {
            return template.getInstallSshCommand();
        }
        return DEFAULT_INSTALL_SSH.get(template.getPackageManager());
    }

    public String resolveInstallXrdpCommand(Template template) {
        if (template.getInstallXrdpCommand() != null && !template.getInstallXrdpCommand().isBlank()) {
            return template.getInstallXrdpCommand();
        }
        return DEFAULT_INSTALL_XRDP.get(template.getPackageManager());
    }

    public String resolveInstallVncCommand(Template template) {
        if (template.getInstallVncCommand() != null && !template.getInstallVncCommand().isBlank()) {
            return template.getInstallVncCommand();
        }
        return DEFAULT_INSTALL_VNC.get(template.getPackageManager());
    }

    /** A shell command with a single {@code %s} for the desktop-session command - see
     *  Template.vncXstartupTemplate's own comment. */
    public String resolveVncXstartupTemplate(Template template) {
        if (template.getVncXstartupTemplate() != null && !template.getVncXstartupTemplate().isBlank()) {
            return template.getVncXstartupTemplate();
        }
        return DEFAULT_VNC_XSTARTUP_TEMPLATE;
    }

    /** See Template.vncProcessNamePattern's own comment. */
    public String resolveVncProcessNamePattern(Template template) {
        if (template.getVncProcessNamePattern() != null && !template.getVncProcessNamePattern().isBlank()) {
            return template.getVncProcessNamePattern();
        }
        return DEFAULT_VNC_PROCESS_NAME_PATTERN;
    }

    /** Null for NONE - nothing to install, matching today's RDP-only behavior with no desktop manager. */
    public String resolveInstallDesktopCommand(Template template, DesktopManager desktopManager) {
        if (desktopManager == DesktopManager.NONE) {
            return null;
        }
        String override = desktopInstallCommandOverride(template, desktopManager);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return DEFAULT_INSTALL_DESKTOP.get(desktopManager).get(template.getPackageManager());
    }

    private static String desktopInstallCommandOverride(Template template, DesktopManager desktopManager) {
        return switch (desktopManager) {
            case GNOME -> template.getInstallGnomeCommand();
            case KDE_STANDARD -> template.getInstallKdeStandardCommand();
            case XFCE4 -> template.getInstallXfce4Command();
            case NONE -> null;
        };
    }

    private static String desktopPreDownloadPackagesOverride(Template template, DesktopManager desktopManager) {
        return switch (desktopManager) {
            case GNOME -> template.getGnomePreDownloadPackages();
            case KDE_STANDARD -> template.getKdeStandardPreDownloadPackages();
            case XFCE4 -> template.getXfce4PreDownloadPackages();
            case NONE -> null;
        };
    }

    // Package-manager-aware, mirroring each DEFAULT_INSTALL_* map's own plain package name(s) above
    // exactly - a flat, single-package-manager list here would pre-fetch the wrong (or nonexistent)
    // package name once a non-APT manager is actually eligible for pre-fetch too (see
    // PRE_DOWNLOAD_ELIGIBLE_MANAGERS below). APK is never a key: it's excluded from pre-fetch
    // entirely (see PackageManager#forDependencyPreFetch's own javadoc for why).
    private static final Map<PackageManager, List<String>> SSH_PACKAGES = Map.of(
            PackageManager.APT, List.of("openssh-server"),
            PackageManager.DNF, List.of("openssh-server"),
            PackageManager.PACMAN, List.of("openssh")
    );
    private static final Map<PackageManager, List<String>> XRDP_PACKAGES = Map.of(
            PackageManager.APT, List.of("xrdp"),
            // .x86_64 pinned - see DEFAULT_INSTALL_XRDP's own DNF entry for why (a plain "xrdp"
            // pulls in a conflicting i686 openh264 dependency chain). xorg-x11-server-Xorg and
            // xorgxrdp must match that same entry - see its own comment.
            PackageManager.DNF, List.of("xrdp.x86_64", "xorg-x11-server-Xorg.x86_64", "xorgxrdp.x86_64"),
            PackageManager.PACMAN, List.of("xorgxrdp", "xrdp")
    );
    private static final Map<PackageManager, List<String>> VNC_PACKAGES = Map.of(
            PackageManager.APT, List.of("tigervnc-standalone-server"),
            // tigervnc-x11-server - see DEFAULT_INSTALL_VNC's own DNF entry for why.
            PackageManager.DNF, List.of("tigervnc-x11-server"),
            // xterm - see DEFAULT_INSTALL_VNC's own PACMAN entry for why it's bundled here.
            PackageManager.PACMAN, List.of("tigervnc", "xterm")
    );
    // GNOME/KDE deliberately have no DNF entry: their DEFAULT_INSTALL_DESKTOP command is a comps
    // *group* install (`dnf group install`), not a plain named-package one - `dnf --downloadonly`
    // (what the pre-fetch pipeline uses) has no equivalent for resolving/caching an entire comps
    // group's membership ahead of time, only individual packages. Falling through to no pre-fetch
    // for that combination (see desktopPackagesToPreDownload below) leaves the direct in-container
    // group install as the only option there, same as before this pre-fetch widening.
    private static final Map<DesktopManager, Map<PackageManager, List<String>>> DESKTOP_PACKAGES = Map.of(
            DesktopManager.GNOME, Map.of(
                    PackageManager.APT, List.of("task-gnome-desktop"),
                    PackageManager.PACMAN, List.of("gnome")),
            DesktopManager.KDE_STANDARD, Map.of(
                    PackageManager.APT, List.of("kde-standard"),
                    PackageManager.PACMAN, List.of("plasma")),
            DesktopManager.XFCE4, Map.of(
                    PackageManager.APT, List.of("xfce4"),
                    // Must match DEFAULT_INSTALL_DESKTOP's DNF entry above - xfce4-session alone
                    // installs but leaves no window manager/desktop/panel, see that entry's
                    // comment (also covers the added xfce4-terminal).
                    PackageManager.DNF, List.of("xfce4-session", "xfwm4", "xfdesktop", "xfce4-panel", "xfce4-terminal"),
                    PackageManager.PACMAN, List.of("xfce4"))
    );

    /** As {@link #xrdpPackagesToPreDownload}, for the SSH install step. */
    public List<String> sshPackagesToPreDownload(Template template) {
        return resolvePreDownloadPackages(SSH_PACKAGES, template, template.getSshPreDownloadPackages(),
                template.getInstallSshCommand());
    }

    /**
     * Packages ProvisioningService should pre-fetch host-side (see
     * ContainerFilesystemProvisioner#downloadPackagesIntoContainer) before running the in-container
     * xrdp install - empty (skip pre-fetch, fall back to today's in-container-only install) unless
     * the template's package manager is pre-fetch-eligible (see PRE_DOWNLOAD_ELIGIBLE_MANAGERS).
     * An explicit pre-fetch-packages override (Template.xrdpPreDownloadPackages) always wins; failing
     * that, an overridden install command with no explicit package-list override skips pre-fetch
     * entirely (an arbitrary shell-command override can't be safely parsed for package names).
     */
    public List<String> xrdpPackagesToPreDownload(Template template) {
        return resolvePreDownloadPackages(XRDP_PACKAGES, template, template.getXrdpPreDownloadPackages(),
                template.getInstallXrdpCommand());
    }

    public List<String> vncPackagesToPreDownload(Template template) {
        return resolvePreDownloadPackages(VNC_PACKAGES, template, template.getVncPreDownloadPackages(),
                template.getInstallVncCommand());
    }

    public List<String> desktopPackagesToPreDownload(Template template, DesktopManager desktopManager) {
        if (desktopManager == DesktopManager.NONE) {
            return List.of();
        }
        Map<PackageManager, List<String>> defaults = DESKTOP_PACKAGES.get(desktopManager);
        return resolvePreDownloadPackages(defaults, template, desktopPreDownloadPackagesOverride(template, desktopManager),
                desktopInstallCommandOverride(template, desktopManager));
    }

    /**
     * Shared override-then-default resolution for every *PackagesToPreDownload method above: an
     * explicit comma-separated package-list override always wins; failing that, an overridden
     * install command with no package-list override skips pre-fetch entirely (can't safely infer
     * package names from an arbitrary shell command); failing that, the package-manager default.
     */
    private List<String> resolvePreDownloadPackages(Map<PackageManager, List<String>> defaultsByPackageManager,
                                                       Template template, String overridePackages, String overrideCommand) {
        if (!PRE_DOWNLOAD_ELIGIBLE_MANAGERS.contains(template.getPackageManager())) {
            return List.of();
        }
        if (overridePackages != null && !overridePackages.isBlank()) {
            return parsePackageList(overridePackages);
        }
        if (overrideCommand != null && !overrideCommand.isBlank()) {
            return List.of();
        }
        return defaultsByPackageManager == null ? List.of()
                : defaultsByPackageManager.getOrDefault(template.getPackageManager(), List.of());
    }

    /** Splits a comma-separated package-list override into individual, trimmed, non-blank names. */
    private static List<String> parsePackageList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static final Set<PackageManager> PRE_DOWNLOAD_ELIGIBLE_MANAGERS =
            Set.of(PackageManager.forDependencyPreFetch());

    /**
     * Every editable Template field, bundled - create/update were already at 12 positional
     * parameters before the pre-fetch/desktop override fields existed; +9 more would make a
     * 21-parameter method, where two adjacent String/boolean params are easy to transpose by
     * accident. Same fix SettingsService.SettingsUpdate already applies to that service's own
     * analogous problem.
     */
    public record TemplateFields(
            String name, String description, String sourcePath, ContainerBackend backend, PackageManager packageManager,
            String installSshCommand, boolean sshPreinstalled, String sshPreDownloadPackages,
            String installXrdpCommand, boolean rdpCapable, String xrdpPreDownloadPackages,
            String installVncCommand, boolean vncCapable, String vncPreDownloadPackages, String vncXstartupTemplate,
            String vncProcessNamePattern,
            String installGnomeCommand, String gnomePreDownloadPackages,
            String installKdeStandardCommand, String kdeStandardPreDownloadPackages,
            String installXfce4Command, String xfce4PreDownloadPackages,
            PrivateUsersMode privateUsersMode, boolean active) {
    }

    @Transactional
    public Template create(TemplateFields fields) {
        requireNameAvailable(fields.name(), null);
        Template template = new Template();
        applyFields(template, fields);
        return templateRepository.save(template);
    }

    @Transactional
    public Template update(Long id, TemplateFields fields) {
        Template template = getById(id);
        requireNameAvailable(fields.name(), id);
        applyFields(template, fields);
        return templateRepository.save(template);
    }

    private static void applyFields(Template template, TemplateFields fields) {
        template.setName(fields.name());
        template.setDescription(fields.description());
        template.setSourcePath(fields.sourcePath());
        template.setBackend(fields.backend());
        template.setPackageManager(fields.packageManager());
        template.setInstallSshCommand(fields.installSshCommand());
        template.setSshPreinstalled(fields.sshPreinstalled());
        template.setSshPreDownloadPackages(fields.sshPreDownloadPackages());
        template.setInstallXrdpCommand(fields.installXrdpCommand());
        template.setRdpCapable(fields.rdpCapable());
        template.setXrdpPreDownloadPackages(fields.xrdpPreDownloadPackages());
        template.setInstallVncCommand(fields.installVncCommand());
        template.setVncCapable(fields.vncCapable());
        template.setVncPreDownloadPackages(fields.vncPreDownloadPackages());
        template.setVncXstartupTemplate(fields.vncXstartupTemplate());
        template.setVncProcessNamePattern(fields.vncProcessNamePattern());
        template.setInstallGnomeCommand(fields.installGnomeCommand());
        template.setGnomePreDownloadPackages(fields.gnomePreDownloadPackages());
        template.setInstallKdeStandardCommand(fields.installKdeStandardCommand());
        template.setKdeStandardPreDownloadPackages(fields.kdeStandardPreDownloadPackages());
        template.setInstallXfce4Command(fields.installXfce4Command());
        template.setXfce4PreDownloadPackages(fields.xfce4PreDownloadPackages());
        template.setPrivateUsersMode(fields.privateUsersMode());
        template.setActive(fields.active());
    }

    @Transactional
    public void setActive(Long id, boolean active) {
        Template template = getById(id);
        template.setActive(active);
        templateRepository.save(template);
    }

    @Transactional
    public void delete(Long id) {
        Template template = getById(id);
        if (containerRepository.existsByTemplate_Id(id)) {
            throw new IllegalStateException(
                    "Cannot delete template '" + template.getName() + "': one or more containers still reference it. Deactivate it instead.");
        }
        templateRepository.delete(template);
    }

    /**
     * Downloads a real minirootfs for {@code flavor} and registers it under that flavor's own fixed
     * name — backs the Templates admin page's independent "Set up X-minimal" buttons, each shown
     * only while that specific flavor's template doesn't already exist yet (setting one up doesn't
     * hide the others - see {@link MinimalTemplateFlavor}). Deliberately not {@code @Transactional}:
     * the filesystem step is a slow network download over SSH, not something to hold a DB
     * transaction open for — the actual write happens in {@link #create}'s own short transaction
     * once that's done.
     *
     * <p>No Alpine flavor is offered (this button used to offer alpine-minimal) - Alpine's official
     * minirootfs has no systemd/D-Bus, which every in-container command in this app requires via
     * systemd-run --machine=; confirmed live, that made it fail permanently with "Failed to connect
     * to bus", not a transient boot race worth retrying past.
     */
    public Template createMinimalDefault(MinimalTemplateFlavor flavor, char[] sudoPasswordOverride) {
        if (templateRepository.findByName(flavor.templateName()).isPresent()) {
            throw new IllegalStateException("'" + flavor.templateName() + "' is already set up.");
        }
        if (sudoPasswordOverride == null && settingsService.sshApprovalRequired()) {
            throw new IllegalArgumentException("A sudo password is required — no stored sudo secret is configured (see /admin/settings).");
        }
        filesystemProvisioner.createMinimalTemplate(flavor, sudoPasswordOverride);
        // Confirmed live: xrdp/xorgxrdp were dropped from Arch's official repos entirely (AUR-only
        // now, which this app has no way to build from) - "target not found" for both, every time.
        // rdpCapable=false here is what actually disables the "Enable RDP" checkbox on the New
        // container form (see create.html/create.js) - not a hardcoded PACMAN check there, so an
        // admin can still flip it back on later (e.g. if a future Arch release restores the
        // package, or a template is hand-edited to a different install command) via the template's
        // own "RDP capable" checkbox on its edit form.
        boolean rdpCapable = flavor.packageManager() != PackageManager.PACMAN;
        // true: every nspawnmgr-create-*-template.sh bake script already installs and enables
        // openssh(-server) as part of baking the image itself (see e.g.
        // nspawnmgr-create-debian-template.sh's own "systemctl enable ssh" step) - see
        // Template.sshPreinstalled's own comment for what this skips at provisioning time.
        return create(new TemplateFields(flavor.templateName(), flavor.description(), flavor.templateName(),
                ContainerBackend.SYSTEMD_NSPAWN, flavor.packageManager(),
                null, true, null,
                null, rdpCapable, null,
                null, true, null, null, null,
                null, null, null, null, null, null,
                minimalFlavorPrivateUsersMode(flavor), true));
    }

    /** IDENTITY only for Fedora - confirmed live (yoga/fed1) this is what's actually needed for
     *  PAM helpers there to read /etc/shadow correctly (see NspawnSettingsRenderer/PrivateUsersMode).
     *  Debian/Arch stay on the host's own systemd default until/unless the same issue shows up there
     *  too - see NspawnSettingsRenderer's own comment for why this isn't applied blanket. */
    private static PrivateUsersMode minimalFlavorPrivateUsersMode(MinimalTemplateFlavor flavor) {
        return flavor == MinimalTemplateFlavor.FEDORA ? PrivateUsersMode.IDENTITY : null;
    }

    /**
     * Registers {@code flavor}'s tarball as a real Template row if one doesn't already exist AND the
     * tarball is already sitting on disk — without re-baking it, unlike {@link #createMinimalDefault}.
     * Covers the self-hosted install path: {@code nspawnmgr-bootstrap-app-machine.sh} (and, in turn,
     * {@code nspawnmgr-bootstrap-db-machine.sh}) bakes or copies {@code debian-minimal.tar.gz} into
     * the templates dir at .deb-install time, before nspawnmgr's own database even exists yet to
     * record a Template row in — this is what backfills that row once the app is actually up. Returns
     * empty (does nothing) if the tarball isn't present either; safe to call on every discovery pass,
     * not just once, since the existing-row check makes it idempotent.
     */
    @Transactional
    public Optional<Template> registerExistingMinimal(MinimalTemplateFlavor flavor) {
        Optional<Template> existing = templateRepository.findByName(flavor.templateName());
        if (existing.isPresent()) {
            return existing;
        }
        if (!filesystemProvisioner.listAvailableSourceFiles(ContainerBackend.SYSTEMD_NSPAWN).contains(flavor.templateName())) {
            return Optional.empty();
        }
        boolean rdpCapable = flavor.packageManager() != PackageManager.PACMAN;
        // true: same reasoning as createMinimalDefault's own call to create() - the tarball already
        // sitting on disk here was produced by the exact same bake script either way.
        return Optional.of(create(new TemplateFields(flavor.templateName(), flavor.description(), flavor.templateName(),
                ContainerBackend.SYSTEMD_NSPAWN, flavor.packageManager(),
                null, true, null,
                null, rdpCapable, null,
                null, true, null, null, null,
                null, null, null, null, null, null,
                minimalFlavorPrivateUsersMode(flavor), true)));
    }

    /** Suggestions for the "New template" form's source-name field — see {@link
     *  ContainerFilesystemProvisioner#listAvailableSourceFiles}. */
    public List<String> listAvailableSourceFiles(ContainerBackend backend) {
        return filesystemProvisioner.listAvailableSourceFiles(backend);
    }

    /**
     * Packs {@code container}'s current rootfs into a brand-new, independent template - backs the
     * container detail page's "Create template from this machine" button. Deliberately not
     * {@code @Transactional}: the filesystem step (tar over SSH) is the slow part, not something to
     * hold a DB transaction open for - the actual row write happens in {@link #create}'s own short
     * transaction once that's done, same posture as {@link #createMinimalDefault}. The new
     * template's backend/package manager/RDP+VNC capability/PrivateUsers mode are inherited from the
     * container's own originating template, since a machine cloned from an APT/RDP-capable/
     * IDENTITY-user-namespace template obviously still is one.
     *
     * <p>Caller is responsible for only invoking this while the container is STOPPED - see {@link
     * ContainerFilesystemProvisioner#packMachineAsTemplate}'s own javadoc for why.
     */
    public Template createFromMachine(String templateName, String description, Container container, char[] sudoPasswordOverride) {
        requireNameAvailable(templateName, null);
        if (sudoPasswordOverride == null && settingsService.sshApprovalRequired()) {
            throw new IllegalArgumentException("A sudo password is required — no stored sudo secret is configured (see /admin/settings).");
        }
        // container.getTemplate() may be a lazy proxy tied to whatever session loaded it in the
        // controller (open-in-view is off) - confirmed live, LazyInitializationException - re-fetch
        // with template eagerly joined, same fix PackageCacheService.installOnContainer already
        // needed for the exact same reason.
        Container freshContainer = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        Template originatingTemplate = freshContainer.getTemplate();
        filesystemProvisioner.packMachineAsTemplate(freshContainer.getName(), originatingTemplate.getBackend(), templateName, sudoPasswordOverride);
        // sshPreinstalled=true unconditionally (not inherited from originatingTemplate): reaching
        // STOPPED at all means this container went through the full create+provision flow, which
        // always runs provisionSsh unconditionally - so the rootfs being packed right now has
        // openssh installed and enabled regardless of whether the ORIGINATING template already had
        // it baked in or not.
        return create(new TemplateFields(templateName, description, templateName,
                originatingTemplate.getBackend(), originatingTemplate.getPackageManager(),
                null, true, null,
                null, originatingTemplate.isRdpCapable(), null,
                null, originatingTemplate.isVncCapable(), null, originatingTemplate.getVncXstartupTemplate(),
                originatingTemplate.getVncProcessNamePattern(),
                null, null, null, null, null, null,
                originatingTemplate.getPrivateUsersMode(), true));
    }

    private void requireNameAvailable(String name, Long excludingId) {
        templateRepository.findByName(name).ifPresent(existing -> {
            if (excludingId == null || !existing.getId().equals(excludingId)) {
                throw new IllegalArgumentException("A template named '" + name + "' already exists");
            }
        });
    }
}
