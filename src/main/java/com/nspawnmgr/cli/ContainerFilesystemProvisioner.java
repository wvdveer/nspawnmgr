package com.nspawnmgr.cli;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Template;

import java.time.Duration;
import java.util.List;

/**
 * Template cloning and .nspawn settings-file writing. The fake (dev) implementation does this as
 * plain local filesystem work; the real implementation runs it as remote shell commands over SSH,
 * since those paths are root-owned on the container host and Tomcat has no local write access.
 */
public interface ContainerFilesystemProvisioner {

    /**
     * Creation-time only (the sole caller is ProvisioningService.provision()) — requires a sudo
     * password rather than a NOPASSWD grant, same as ContainerCliExecutor.runInMachine.
     * {@code sudoPasswordOverride} non-null uses that instead of the configured stored password.
     */
    void cloneTemplate(Template template, String machineName, char[] sudoPasswordOverride);

    /** Convenience overload for stored-secret mode (no per-request override). */
    default void cloneTemplate(Template template, String machineName) {
        cloneTemplate(template, machineName, null);
    }

    /**
     * Writes the [Network] Port= forwarding entries for the container's SSH/RDP and custom
     * mappings. Called both at creation and any time an owner edits port mappings on an existing
     * container (ContainerPortMappingService) — must never block on approval mode either way.
     */
    void writeNspawnSettings(Container container, List<ContainerPortMapping> customMappings);

    void deleteMachineFiles(String machineName);

    /**
     * Downloads a real minirootfs for the given flavor, bakes openssh-server into it, and packs it
     * as a machinectl import-tar-compatible gzipped tar at
     * {@code <templates-dir>/nspawn/<flavor.templateName()>.tar.gz} — backs the Templates admin
     * page's independent "Set up X-minimal" buttons (each shown only while that specific flavor's
     * template doesn't already exist - setting one up doesn't hide the others). Creation-time-only,
     * same sudo-password requirement as {@link #cloneTemplate}. Throws if the target already exists.
     *
     * <p>Deliberately no Alpine flavor: Alpine's official minirootfs has no systemd/D-Bus at all (it
     * uses OpenRC), and every in-container command in this app goes through
     * {@code systemd-run --machine=}, which requires the container itself to be running systemd -
     * confirmed live, an Alpine-based container fails "Failed to connect to bus" permanently, not as
     * a transient boot race. See {@link MinimalTemplateFlavor}'s own javadoc for which of the three
     * offered flavors have actually been verified live (only DEBIAN) versus built to each distro's
     * documented conventions but never exercised (FEDORA, ARCH).
     */
    void createMinimalTemplate(MinimalTemplateFlavor flavor, char[] sudoPasswordOverride);

    /** Convenience overload for stored-secret mode (no per-request override). */
    default void createMinimalTemplate(MinimalTemplateFlavor flavor) {
        createMinimalTemplate(flavor, null);
    }

    /**
     * Packs {@code machineName}'s current rootfs into a new template tarball at
     * {@code <templates-dir>/<backend's subdirectory>/<templateName>.tar.gz} - backs the container
     * detail page's "Create template from this machine" button. Creation-time-only, same
     * sudo-password requirement as {@link #cloneTemplate}: the resulting tarball becomes something
     * other users' containers can later be cloned from, the same trust boundary that already gates
     * every other template-producing operation here. Throws if the target already exists.
     *
     * <p>The caller is responsible for only invoking this while the container is STOPPED - packing
     * a live rootfs risks an inconsistent archive as files change mid-tar; this method itself
     * doesn't check container state, it only knows the machine name.
     */
    void packMachineAsTemplate(String machineName, ContainerBackend backend, String templateName, char[] sudoPasswordOverride);

    /** Convenience overload for stored-secret mode (no per-request override). */
    default void packMachineAsTemplate(String machineName, ContainerBackend backend, String templateName) {
        packMachineAsTemplate(machineName, backend, templateName, null);
    }

    /**
     * Downloads {@code packages} (with their full dependency closure) as a host-side process
     * pointed at the container's own rootfs directory - the same technique
     * {@link #createMinimalTemplate} already uses, since apt/DNS resolution issued from
     * *inside* a running container has been confirmed unreliable even when the host's own network
     * works fine. No-ops immediately (returning an empty list) if {@code packages} is empty (see
     * {@code TemplateService}'s {@code *PackagesToPreDownload} methods for when that's the case -
     * only APT templates with an unoverridden install command are eligible for the SSH/RDP/VNC/
     * desktop-manager provisioning-time caller; {@code PackageCacheService.installOnContainer}'s
     * manual "Install package" flow also uses this, for APT, DNF, and PACMAN). Creation/setup-time
     * only, same sudo-password requirement as {@link #cloneTemplate}.
     *
     * <p>{@code packageManager} picks which real script/cache-directory convention to use - APT,
     * DNF, and PACMAN each have entirely different CLI syntax for this, so this isn't a shared
     * implementation underneath, just a shared method shape. DNF and PACMAN support is unverified:
     * no RPM/Fedora/RHEL or Arch host exists anywhere in this project's test environment, so both
     * are built to their documented CLI contract as carefully as possible but have never been
     * exercised live (same best-effort/unverified status they already have everywhere else in this
     * app - PACMAN more so than DNF, see {@link #missingDependenciesFor}'s own javadoc).
     *
     * <p>Returns one {@link DownloadedPackage} per explicitly-requested top-level package (not its
     * transitive dependencies, which stay a cache-directory implementation detail) - the caller
     * registers these with PackageCacheService so they show up in /admin/packages instead of only
     * being usable as a hidden side effect of this one run.
     *
     * @param timeout how long to let the whole host-side apt/dnf/pacman sequence run before giving
     *                up - confirmed live, a large desktop metapackage (task-gnome-desktop, ~1000+
     *                packages including dependencies) can genuinely take longer to *fetch* over a
     *                real network connection than the small single-package SSH/RDP/VNC fetches this
     *                same method also handles, the same lesson {@link
     *                com.nspawnmgr.service.ProvisioningService}'s own desktop-install exec timeout
     *                already learned for the separate in-container *install* step.
     */
    List<DownloadedPackage> downloadPackagesIntoContainer(PackageManager packageManager, String machineName,
                                                            List<String> packages, char[] sudoPasswordOverride, Duration timeout);

    /** Convenience overload using this call's default timeout - fine for the small single-package
     *  SSH/RDP/VNC provisioning fetches; large desktop-manager installs need the explicit-timeout
     *  overload above instead. */
    default List<DownloadedPackage> downloadPackagesIntoContainer(PackageManager packageManager, String machineName,
                                                                    List<String> packages, char[] sudoPasswordOverride) {
        return downloadPackagesIntoContainer(packageManager, machineName, packages, sudoPasswordOverride, Duration.ofMinutes(5));
    }

    /** Convenience overload for stored-secret mode (no per-request override). */
    default List<DownloadedPackage> downloadPackagesIntoContainer(PackageManager packageManager, String machineName, List<String> packages) {
        return downloadPackagesIntoContainer(packageManager, machineName, packages, null);
    }

    /**
     * Determines which dependencies of an arbitrary local package file aren't already satisfied on
     * {@code machineName}'s rootfs, without installing anything - used by
     * PackageCacheService.installOnContainer's manual "Install package" flow so those missing
     * dependencies can be pre-fetched via {@link #downloadPackagesIntoContainer} before the actual
     * in-container install runs, instead of leaving a missing dependency as a visible install
     * failure. Returns bare package names (not filenames), the same shape
     * {@link #downloadPackagesIntoContainer}'s own {@code packages} parameter expects. Read/simulate
     * only (apt/dnf's -s/--assumeno, pacman's -U --print, nothing actually changes), but still
     * requires a sudo password: like {@link #downloadPackagesIntoContainer}, it refreshes the
     * container's own package index host-side first. APT, DNF, and PACMAN are supported - see
     * {@link #downloadPackagesIntoContainer}'s own javadoc for why APK isn't (APK containers don't
     * fully work in this app at all). PACMAN support is the most speculative of the three: unlike
     * apt/dnf's own well-documented dry-run flags, pacman's {@code -U --print} semantics for a full
     * local-file dependency-closure simulation have never been exercised anywhere in this project,
     * not even manually - built to the documented pacman(8) contract as carefully as possible.
     *
     * @param localPackageHostPath host-visible path to the uploaded package file (not necessarily
     *                             under {@code machineName}'s own rootfs - it lives in the admin
     *                             package cache)
     */
    List<String> missingDependenciesFor(PackageManager packageManager, String machineName, String localPackageHostPath,
                                         char[] sudoPasswordOverride);

    /** Convenience overload for stored-secret mode (no per-request override). */
    default List<String> missingDependenciesFor(PackageManager packageManager, String machineName, String localPackageHostPath) {
        return missingDependenciesFor(packageManager, machineName, localPackageHostPath, null);
    }

    /**
     * Lists the bare stems (no {@code .tar.gz} extension) of every template tarball already
     * present under {@code <templates-dir>/<backend's subdirectory>} — backs the "New template"
     * admin form's source-name suggestions, so an admin doesn't have to type an exact filename from
     * memory. Read-only and always safe, so unlike {@link #cloneTemplate}/{@link
     * #createMinimalTemplate} this needs no sudo password at all (NOPASSWD wrapper script, same tier
     * as {@code ContainerCliExecutor#listMachineImageNames}). Best-effort: an inaccessible or
     * missing directory returns an empty list rather than throwing, since this is only ever a
     * suggestion, never something a caller depends on to proceed.
     */
    List<String> listAvailableSourceFiles(ContainerBackend backend);
}
