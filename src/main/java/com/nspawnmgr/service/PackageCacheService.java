package com.nspawnmgr.service;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.CachedPackageRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-curated package upload cache: admins upload arbitrary .deb/.rpm/.iso/etc. files here, and
 * any container owner can either install one (matching their container's own package manager - see
 * {@link #installOnContainer}) or, for a packageManager=ISO row, mount it onto a container instead
 * (see ContainerLifecycleService.mountIso) - a fundamentally different operation (read-only, at most
 * one per container, live-unmounted rather than uninstalled), but the same underlying cache/upload
 * machinery, per an explicit decision to keep ISOs as just another PackageManager value here rather
 * than a separate entity/table/admin-page. The top-level packages nspawnmgr auto-fetches for its own
 * SSH/RDP/VNC/desktop-manager provisioning (see
 * ContainerFilesystemProvisioner#downloadPackagesIntoContainer and #registerAutoFetchedIfAbsent
 * below) also land here, for visibility/reuse - confirmed live, that page previously had no
 * visibility into anything this path had already downloaded onto the host. Their transitive
 * dependencies stay a cache-directory implementation detail only, not registered individually.
 * {@link #installOnContainer} does resolve dependencies automatically for APT/DNF/PACMAN (simulate,
 * pre-fetch anything missing, then run the package manager's own local-install command - see that
 * method's own javadoc); APK stays a single local install (apk add) with no dependency-closure
 * resolution, same unverified/best-effort status it already has elsewhere in this app - a missing
 * dependency there still shows up as a visible error in the captured output.
 */
@Service
public class PackageCacheService {

    private static final String CACHE_ROOT = "/var/cache/nspawnmgr/packages";
    private static final String IN_CONTAINER_SUBDIR = "nspawnmgr-packages";

    // APT installs through apt-get, not a raw `dpkg -i` - once the host-side simulate+pre-fetch
    // step below has populated the container's own local archive cache with anything missing,
    // apt-get's own dependency resolution installs both the uploaded file and those dependencies
    // in one coherent, correctly-ordered pass, the same mechanism ProvisioningService's own
    // SSH/RDP/VNC/desktop-manager installs already rely on. `dpkg -i` alone never did this - it
    // only ever installs the exact file(s) you hand it, so any dependency the container didn't
    // already have showed up as a visible "not installed" error instead of being resolved.
    //
    // DNF's own missing-dependency pre-fetch (above) goes through the same
    // nspawnmgr-download-packages-dnf.sh as ProvisioningService's installs - see
    // TemplateService.DEFAULT_INSTALL_SSH's own comment: it writes a flat, non-hashed copy to
    // /var/cache/dnf/, not dnf5's real (dynamically-hashed) cache location, so those pre-fetched
    // dependency .rpms need to be handed to dnf explicitly as install arguments or they're silently
    // invisible to it. Unlike the provisioning-time installs there's always a guaranteed argument
    // here regardless (the uploaded file's own %s), so this appends whatever local dependency files
    // exist rather than needing an ls-guarded either/or fallback - an empty `$(...)` command
    // substitution just contributes no extra words when nothing was pre-fetched.
    private static final Map<PackageManager, String> LOCAL_INSTALL_COMMAND = Map.of(
            PackageManager.APT, "DEBIAN_FRONTEND=noninteractive apt-get install -y %s",
            PackageManager.DNF, "dnf install -y %s $(ls /var/cache/dnf/*.rpm 2>/dev/null)",
            PackageManager.APK, "apk add --allow-untrusted %s",
            PackageManager.PACMAN, "pacman -U --noconfirm %s"
    );

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

    private final CachedPackageRepository repository;
    private final PackageCacheFilesystem filesystem;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerFilesystemProvisioner filesystemProvisioner;
    private final SettingsService settingsService;
    private final ContainerRepository containerRepository;

    public PackageCacheService(CachedPackageRepository repository, PackageCacheFilesystem filesystem,
                                ContainerCliExecutor cliExecutor, ContainerFilesystemProvisioner filesystemProvisioner,
                                SettingsService settingsService, ContainerRepository containerRepository) {
        this.repository = repository;
        this.filesystem = filesystem;
        this.cliExecutor = cliExecutor;
        this.filesystemProvisioner = filesystemProvisioner;
        this.settingsService = settingsService;
        this.containerRepository = containerRepository;
    }

    @Transactional
    public CachedPackage upload(PackageManager packageManager, String originalFilename, InputStream content,
                                 long sizeBytes, String description, User admin) {
        String storedFilename = UUID.randomUUID() + "_" + sanitizeFilename(originalFilename);
        filesystem.upload(uploadedDir(packageManager) + "/" + storedFilename, content);
        CachedPackage cachedPackage = new CachedPackage(packageManager, originalFilename, storedFilename,
                description, admin, sizeBytes);
        return repository.save(cachedPackage);
    }

    /**
     * Registers a package nspawnmgr auto-downloaded for its own RDP/VNC/desktop-manager/SSH
     * provisioning (see ContainerFilesystemProvisioner#downloadPackagesIntoContainer) as a
     * CachedPackage, so it's visible/reusable from /admin/packages instead of only ever having been
     * a hidden side effect of that one provisioning run - confirmed live, that page had zero
     * visibility into anything this path had already fetched onto the host. The .deb itself was
     * already placed directly under this package manager's own "uploaded" directory (host-side,
     * outside any Java process - see the download script), so unlike {@link #upload}, this only
     * needs a DB row, not a filesystem write. Idempotent: a second provisioning run requesting the
     * same package (e.g. a second XFCE4 container) is a no-op past the first, keyed on the stored
     * filename apt itself determines (name+version+arch), not the request that triggered it.
     */
    @Transactional
    public void registerAutoFetchedIfAbsent(PackageManager packageManager, String originalFilename, long sizeBytes, User owner) {
        String storedFilename = "auto-" + originalFilename;
        if (repository.existsByPackageManagerAndStoredFilename(packageManager, storedFilename)) {
            return;
        }
        CachedPackage cachedPackage = new CachedPackage(packageManager, originalFilename, storedFilename,
                "Auto-downloaded during container provisioning", owner, sizeBytes);
        repository.save(cachedPackage);
    }

    @Transactional
    public void delete(Long id) {
        CachedPackage cachedPackage = getById(id);
        if (containerRepository.existsByMountedIso_Id(id)) {
            throw new IllegalStateException(
                    "Cannot delete '" + cachedPackage.getOriginalFilename() + "': still mounted on a container. Eject it first.");
        }
        filesystem.delete(uploadedDir(cachedPackage.getPackageManager()) + "/" + cachedPackage.getStoredFilename());
        repository.delete(cachedPackage);
    }

    public CachedPackage getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such cached package: " + id));
    }

    public List<CachedPackage> listFor(PackageManager packageManager) {
        return repository.findByPackageManagerOrderByOriginalFilename(packageManager);
    }

    public List<CachedPackage> listAll() {
        return repository.findAllWithUploadedBy();
    }

    // Package managers whose local-install command doesn't already resolve missing dependencies on
    // its own (dpkg -i never did; dnf/pacman's own local-file install normally would, but the
    // explicit design choice here is to fetch dependencies host-side and cache them into the
    // container regardless, matching APT's own posture, rather than letting the container reach the
    // network directly). See PackageManager.forDependencyPreFetch()'s own javadoc for why APK is
    // excluded.
    private static final Set<PackageManager> DEPENDENCY_PRE_FETCH_MANAGERS = Set.of(PackageManager.forDependencyPreFetch());

    /**
     * Copies the chosen cached package into the container's own filesystem, then (for APT/DNF - see
     * {@link ContainerFilesystemProvisioner#missingDependenciesFor}) simulates the install to find
     * any dependency the container doesn't already have, downloads those onto the host and
     * registers them in this same cache (so they show up in /admin/packages instead of only ever
     * being a hidden side effect of this one install), and finally runs the package-manager-
     * appropriate local install command inside the container via the existing {@link
     * ContainerCliExecutor#runInMachine}. Confirmed live: a plain `dpkg -i` on an APT package with
     * unmet dependencies just failed with a visible "not installed" error - apt-get's own
     * dependency resolution (see {@link #LOCAL_INSTALL_COMMAND}) now installs both the uploaded
     * file and whatever this pre-fetch step just cached, in one coherent pass. DNF/PACMAN's own local
     * install would resolve dependencies over the network on their own, but the pre-fetch happens
     * anyway for consistency with APT - deliberately never letting a container reach the network
     * directly for this. DNF and PACMAN support is unverified (see {@link ContainerFilesystemProvisioner}'s
     * own javadoc - no RPM/Fedora/RHEL or Arch host exists anywhere in this project's test
     * environment; PACMAN is the more speculative of the two).
     *
     * <p>{@code sudoPasswordOverride} null falls back to the configured stored sudo secret, same as
     * every other self-service action in this app - note the simulate/pre-fetch sub-step (like
     * {@link ContainerFilesystemProvisioner#downloadPackagesIntoContainer}) requires a real sudo
     * password tier, so an APT/DNF install with missing dependencies fails outright (no silent
     * partial install) if no stored secret is configured and none was supplied.
     */
    @Transactional
    public CommandResult installOnContainer(Container container, Long cachedPackageId, char[] sudoPasswordOverride) {
        // container.getTemplate() may be a lazy proxy tied to whatever session loaded it in the
        // controller (open-in-view is off) - re-fetch with template eagerly joined in this method's
        // own transaction rather than trust that reference.
        Container freshContainer = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        CachedPackage cachedPackage = getById(cachedPackageId);
        PackageManager containerPackageManager = freshContainer.effectivePackageManager();
        if (containerPackageManager == null) {
            throw new IllegalStateException(freshContainer.getName() + " has no template and no package manager set - "
                    + "set one on the container detail page before installing a package.");
        }
        if (cachedPackage.getPackageManager() != containerPackageManager) {
            throw new IllegalArgumentException("Package '" + cachedPackage.getOriginalFilename() + "' is for "
                    + cachedPackage.getPackageManager() + ", but " + freshContainer.getName() + " uses " + containerPackageManager);
        }
        String sourcePath = uploadedDir(cachedPackage.getPackageManager()) + "/" + cachedPackage.getStoredFilename();
        String inContainerPath = "/root/" + IN_CONTAINER_SUBDIR + "/" + cachedPackage.getStoredFilename();

        if (freshContainer.getBackend() == ContainerBackend.PODMAN) {
            // PODMAN: podman cp addresses the container directly (no host-visible rootfs path to
            // copy into or chroot a dependency simulation against - see PackageCacheFilesystem
            // .copyIntoPodmanContainer's own javadoc) - and, unlike SYSTEMD_NSPAWN, a podman
            // container has real network access of its own (it's on the shared nspawnbr0 bridge),
            // so the local install command below can resolve dependencies itself; no host-side
            // pre-fetch step for podman containers in this first pass.
            filesystem.copyIntoPodmanContainer(sourcePath, freshContainer.getName(), inContainerPath);
        } else {
            String hostSideDestDir = Path.of(settingsService.nspawnMachinesDir(), freshContainer.getName(), "root", IN_CONTAINER_SUBDIR).toString();
            filesystem.copyIntoContainer(sourcePath, hostSideDestDir);

            if (DEPENDENCY_PRE_FETCH_MANAGERS.contains(containerPackageManager)) {
                List<String> missingDependencies = filesystemProvisioner.missingDependenciesFor(
                        containerPackageManager, freshContainer.getName(), sourcePath, sudoPasswordOverride);
                if (!missingDependencies.isEmpty()) {
                    List<DownloadedPackage> downloaded = filesystemProvisioner.downloadPackagesIntoContainer(
                            containerPackageManager, freshContainer.getName(), missingDependencies, sudoPasswordOverride);
                    for (DownloadedPackage p : downloaded) {
                        registerAutoFetchedIfAbsent(containerPackageManager, p.filename(), p.sizeBytes(), cachedPackage.getUploadedBy());
                    }
                }
            }
        }

        String command = LOCAL_INSTALL_COMMAND.get(cachedPackage.getPackageManager()).formatted(inContainerPath);
        return cliExecutor.runInMachine(freshContainer.getName(), freshContainer.getBackend(), List.of("sh", "-c", command), INSTALL_TIMEOUT, sudoPasswordOverride);
    }

    /**
     * Lists every file already sitting in {@code packageManager}'s shared "auto" cache directory
     * ({@code CACHE_ROOT/<manager>/auto}) - the transitive dependencies {@link
     * ContainerFilesystemProvisioner#downloadPackagesIntoContainer} has fetched onto the host but
     * deliberately never registers individually as a {@link CachedPackage} row (see this class's
     * own javadoc for why - with ~450 dependencies for a single desktop install, that table would be
     * noise, not a usable feature). Generated fresh on every call by listing the real directory,
     * never persisted anywhere - backs the admin Packages page's "Show transitive dependencies"
     * button. Empty for any package manager outside {@link PackageManager#forDependencyPreFetch()},
     * since only those have such a directory at all.
     */
    public List<DownloadedPackage> listAutoFetched(PackageManager packageManager) {
        if (!DEPENDENCY_PRE_FETCH_MANAGERS.contains(packageManager)) {
            return List.of();
        }
        return filesystem.listFiles(CACHE_ROOT + "/" + packageManager.name().toLowerCase() + "/auto");
    }

    /** Host-visible path to this cached package's file - used directly by ContainerIsoMounter for
     *  packageManager=ISO rows (mounted, never installed), which never go through
     *  {@link #installOnContainer}. */
    public String hostPath(CachedPackage cachedPackage) {
        return uploadedDir(cachedPackage.getPackageManager()) + "/" + cachedPackage.getStoredFilename();
    }

    /** Package-private (not private) so {@link PackageDownloadService} can compute the identical
     *  final on-disk path convention for a host-side URL download, which writes directly to its
     *  final resting place rather than going through {@link #upload}. */
    String uploadedDir(PackageManager packageManager) {
        return CACHE_ROOT + "/" + packageManager.name().toLowerCase() + "/uploaded";
    }

    /**
     * Registers a package a host-side download (not a browser upload) already placed at its final
     * path under {@link #uploadedDir} - {@link PackageDownloadService}'s own equivalent of
     * {@link #registerAutoFetchedIfAbsent}, but for an admin-initiated URL download, which (unlike
     * an auto-fetched dependency) always gets its own row, keyed by a caller-supplied
     * {@code storedFilename} (see that service's own {@code <downloadId>_<sanitized>} convention -
     * deliberately decided up front so the download can write straight to its final path, no
     * separate stage-then-move step).
     */
    @Transactional
    public CachedPackage registerDownloaded(PackageManager packageManager, String originalFilename, String storedFilename,
                                             String description, User admin, long sizeBytes) {
        CachedPackage cachedPackage = new CachedPackage(packageManager, originalFilename, storedFilename,
                description, admin, sizeBytes);
        return repository.save(cachedPackage);
    }

    /** Package-private (not private) - see {@link #uploadedDir}'s own comment for why
     *  {@link PackageDownloadService} needs this too. */
    static String sanitizeFilename(String originalFilename) {
        String base = Path.of(originalFilename).getFileName().toString();
        String stripped = base.replaceAll("[/\\\\]", "_");
        while (stripped.startsWith(".")) {
            stripped = stripped.substring(1);
        }
        return stripped.isBlank() ? "package" : stripped;
    }
}
