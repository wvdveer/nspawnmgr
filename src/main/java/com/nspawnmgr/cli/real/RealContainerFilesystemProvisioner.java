package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.NspawnSettingsRenderer;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * templatesDir/machinesDir/settingsDir are root-owned paths on the container host, and Tomcat has
 * no local write access (or sudo) to them, so every operation here runs as a remote shell command
 * over {@link SshRemoteExecutor} rather than as local java.nio.file I/O.
 *
 * <p>{@code cloneTemplate} is creation-time-only (see interface javadoc) and requires a sudo
 * password. The other two methods are routine, always-must-work operations (writeNspawnSettings is
 * also called whenever an owner edits port mappings on an existing container, not just at
 * creation) — they shell out to fixed wrapper scripts under
 * {@code NspawnProperties.privilegedScriptsDir()} (shipped by the .deb, see
 * packaging/nspawnmgr-deb/privileged-scripts/) so the NOPASSWD sudoers grant can match on an exact
 * path rather than having to wildcard-match inline script text.
 */
@Component
@Profile("!dev")
public class RealContainerFilesystemProvisioner implements ContainerFilesystemProvisioner {

    private static final Logger log = LoggerFactory.getLogger(RealContainerFilesystemProvisioner.class);

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;

    public RealContainerFilesystemProvisioner(SettingsService settingsService, SshRemoteExecutor ssh) {
        this.settingsService = settingsService;
        this.ssh = ssh;
    }

    @Override
    public void cloneTemplate(Template template, String machineName, char[] sudoPasswordOverride) {
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : settingsService.sshPassword().toCharArray();
        Path source = Path.of(settingsService.nspawnTemplatesDir(), template.getBackend().templateSubdirectory(),
                template.getSourcePath() + ".tar.gz");
        Path destination = Path.of(settingsService.nspawnMachinesDir(), machineName);
        CommandResult result = ssh.execWithSudoPassword(Duration.ofMinutes(5),
                List.of(wrapperScript("nspawnmgr-clone-template.sh"), source.toString(), destination.toString()),
                null, password);
        if (!result.success()) {
            throw new ContainerCliException("Failed to clone template " + template.getName() + " to " + destination
                    + ": " + result.stderr());
        }
    }

    @Override
    public void writeNspawnSettings(Container container, List<ContainerPortMapping> customMappings) {
        Path settingsFile = Path.of(settingsService.nspawnSettingsDir(), container.getName() + ".nspawn");
        String content = NspawnSettingsRenderer.render(container, customMappings);
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-write-file.sh"), settingsFile.toString()), content);
        if (!result.success()) {
            throw new ContainerCliException("Failed to write .nspawn settings for " + container.getName()
                    + ": " + result.stderr());
        }
    }

    private static final Map<MinimalTemplateFlavor, String> MINIMAL_TEMPLATE_SCRIPT = Map.of(
            MinimalTemplateFlavor.DEBIAN, "nspawnmgr-create-debian-template.sh",
            MinimalTemplateFlavor.FEDORA, "nspawnmgr-create-fedora-template.sh",
            MinimalTemplateFlavor.ARCH, "nspawnmgr-create-arch-template.sh"
    );

    @Override
    public void createMinimalTemplate(MinimalTemplateFlavor flavor, char[] sudoPasswordOverride) {
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : settingsService.sshPassword().toCharArray();
        Path target = Path.of(settingsService.nspawnTemplatesDir(),
                ContainerBackend.SYSTEMD_NSPAWN.templateSubdirectory(), flavor.templateName() + ".tar.gz");
        // 10 minutes, not the 5 Debian's own script has run fine with live: Fedora/Arch base images
        // are meaningfully larger, and neither has been timed against a real host to know if 5 would
        // have been enough.
        CommandResult result = ssh.execWithSudoPassword(Duration.ofMinutes(10),
                List.of(wrapperScript(MINIMAL_TEMPLATE_SCRIPT.get(flavor)), target.toString()),
                null, password);
        if (!result.success()) {
            // Confirmed live (Debian case): systemd-nspawn's own terse wrapper messages ("Spawning
            // container...", "Container X failed with error code N") land in stderr, but the actual
            // reason a command inside the container failed (e.g. apt-get's own error text) goes to
            // stdout - stderr alone gave a completely uninformative "error code 100" with nothing to
            // act on.
            throw new ContainerCliException("Failed to create " + flavor.templateName() + " template at " + target
                    + " (exit " + result.exitCode() + "): " + result.stdout() + " -- " + result.stderr());
        }
    }

    @Override
    public void packMachineAsTemplate(String machineName, ContainerBackend backend, String templateName, char[] sudoPasswordOverride) {
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : settingsService.sshPassword().toCharArray();
        Path rootfs = Path.of(settingsService.nspawnMachinesDir(), machineName);
        Path target = Path.of(settingsService.nspawnTemplatesDir(), backend.templateSubdirectory(), templateName + ".tar.gz");
        // 10 minutes: this is genuinely just tar -cz over the rootfs (no package manager, no
        // network), but a desktop-manager container's rootfs can be large - no measured timing
        // exists yet to know if a shorter budget would've been enough.
        CommandResult result = ssh.execWithSudoPassword(Duration.ofMinutes(10),
                List.of(wrapperScript("nspawnmgr-pack-machine-as-template.sh"), rootfs.toString(), target.toString()),
                null, password);
        if (!result.success()) {
            throw new ContainerCliException("Failed to pack " + machineName + " as template " + templateName
                    + " (exit " + result.exitCode() + "): " + result.stdout() + " -- " + result.stderr());
        }
    }

    // <CACHE_ROOT>/<package manager>/{auto,uploaded} - same convention PackageCacheService's own
    // uploadedDir(packageManager) computes, deliberately re-derived here rather than shared (matches
    // how this was already a separately-hardcoded literal for APT alone, before DNF support).
    private static final String CACHE_ROOT = "/var/cache/nspawnmgr/packages";

    private static final Map<PackageManager, String> DOWNLOAD_SCRIPT = Map.of(
            PackageManager.APT, "nspawnmgr-download-packages.sh",
            PackageManager.DNF, "nspawnmgr-download-packages-dnf.sh",
            PackageManager.PACMAN, "nspawnmgr-download-packages-pacman.sh"
    );
    private static final Map<PackageManager, String> SIMULATE_SCRIPT = Map.of(
            PackageManager.APT, "nspawnmgr-simulate-install.sh",
            PackageManager.DNF, "nspawnmgr-simulate-install-dnf.sh",
            PackageManager.PACMAN, "nspawnmgr-simulate-install-pacman.sh"
    );

    // Both nspawnmgr-download-packages.sh and its DNF sibling take an exclusive lock on their own
    // package manager's cache dir (apt/dnf's own archive-directory lock) for the whole download - a
    // single directory shared across every container/install on this host, by design, so an
    // already-cached package is never re-fetched. Confirmed live on yoga (APT case): two containers
    // created at once (both needing package downloads) raced two concurrent apt-get processes onto
    // that same directory, and the second one's "E: Unable to lock directory" came straight back as
    // a provisioning failure instead of just waiting its turn. One JVM-wide lock (not one per
    // package manager) serializes every download/simulate call regardless of manager - correct
    // since RealContainerFilesystemProvisioner is a singleton bean and this is the only nspawnmgr
    // process touching this host's copy of any of these directories; slightly more conservative
    // than strictly necessary (an APT download no longer needs to wait on a DNF one), but simplicity
    // wins over squeezing out that unlikely-to-matter concurrency.
    private final ReentrantLock downloadLock = new ReentrantLock();

    @Override
    public List<DownloadedPackage> downloadPackagesIntoContainer(PackageManager packageManager, String machineName,
                                                                   List<String> packages, char[] sudoPasswordOverride, Duration timeout) {
        if (packages.isEmpty()) {
            return List.of();
        }
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : settingsService.sshPassword().toCharArray();
        Path rootfs = Path.of(settingsService.nspawnMachinesDir(), machineName);
        // Every DOWNLOAD_SCRIPT entry is host-side now (APT via -o Dir=, DNF/PACMAN via chroot into
        // the rootfs - see nspawnmgr-download-packages-dnf.sh/-pacman.sh's own comments) - none of
        // them need the container's machinectl/systemd-nspawn name at all anymore, only its rootfs
        // path. Confirmed live: downloads issued from *inside* a running container's own network
        // namespace (the old systemd-run --machine= approach DNF/PACMAN both used) have been
        // unreliable even with working host-level network/DNS - a real provisioning run died
        // mid-transfer with "Operation too slow" - the same reasoning APT's own host-side approach
        // was already built around from the start.
        List<String> command = new ArrayList<>(List.of(wrapperScript(DOWNLOAD_SCRIPT.get(packageManager))));
        command.addAll(List.of(rootfs.toString(), autoFetchCacheDir(packageManager), autoFetchUploadDir(packageManager)));
        command.addAll(packages);
        downloadLock.lock();
        try {
            CommandResult result = ssh.execWithSudoPassword(timeout, command, null, password);
            if (!result.success()) {
                throw new ContainerCliException("Failed to download " + packageManager + " packages " + packages
                        + " for " + machineName + " (exit " + result.exitCode() + "): " + result.stdout() + " -- " + result.stderr());
            }
            return DownloadedPackage.parseLines(result.stdout());
        } finally {
            downloadLock.unlock();
        }
    }

    private static String autoFetchCacheDir(PackageManager packageManager) {
        return CACHE_ROOT + "/" + packageManager.name().toLowerCase() + "/auto";
    }

    private static String autoFetchUploadDir(PackageManager packageManager) {
        return CACHE_ROOT + "/" + packageManager.name().toLowerCase() + "/uploaded";
    }

    @Override
    public List<String> missingDependenciesFor(PackageManager packageManager, String machineName,
                                                 String localPackageHostPath, char[] sudoPasswordOverride) {
        if (SIMULATE_SCRIPT.get(packageManager) == null) {
            throw new IllegalArgumentException("Dependency simulation isn't supported for " + packageManager);
        }
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : settingsService.sshPassword().toCharArray();
        Path rootfs = Path.of(settingsService.nspawnMachinesDir(), machineName);
        // Every SIMULATE_SCRIPT entry is host-side now, same as downloadPackagesIntoContainer's own
        // DOWNLOAD_SCRIPT above - none of them need the container's machinectl/systemd-nspawn name.
        List<String> command = List.of(wrapperScript(SIMULATE_SCRIPT.get(packageManager)), rootfs.toString(), localPackageHostPath);
        // Same shared package-state directory the corresponding download script's own index-refresh
        // step touches (both point at this same rootfs) - reuse that lock so a simulate call never
        // races a concurrent download for the same container's own package manager state.
        downloadLock.lock();
        try {
            CommandResult result = ssh.execWithSudoPassword(Duration.ofMinutes(2), command, null, password);
            if (!result.success()) {
                throw new ContainerCliException("Failed to simulate installing " + localPackageHostPath + " on " + machineName
                        + " (exit " + result.exitCode() + "): " + result.stdout() + " -- " + result.stderr());
            }
            return result.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        } finally {
            downloadLock.unlock();
        }
    }

    @Override
    public void deleteMachineFiles(String machineName) {
        Path machineDir = Path.of(settingsService.nspawnMachinesDir(), machineName);
        Path settingsFile = Path.of(settingsService.nspawnSettingsDir(), machineName + ".nspawn");
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(30),
                List.of(wrapperScript("nspawnmgr-delete-machine-files.sh"), machineDir.toString(), settingsFile.toString()));
        if (!result.success()) {
            throw new ContainerCliException("Failed to delete files for machine " + machineName
                    + ": " + result.stderr());
        }
    }

    @Override
    public List<String> listAvailableSourceFiles(ContainerBackend backend) {
        Path dir = Path.of(settingsService.nspawnTemplatesDir(), backend.templateSubdirectory());
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-list-template-files.sh"), dir.toString()));
        if (!result.success()) {
            log.warn("nspawnmgr-list-template-files.sh failed (exit {}): stdout={} stderr={}",
                    result.exitCode(), result.stdout(), result.stderr());
            return List.of();
        }
        return result.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String wrapperScript(String name) {
        return Path.of(settingsService.nspawnPrivilegedScriptsDir(), name).toString();
    }
}
