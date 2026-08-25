package com.nspawnmgr.cli;

import java.io.InputStream;
import java.util.List;

/**
 * Host-side filesystem operations backing the admin-curated package upload cache
 * (PackageCacheService) - distinct from ContainerFilesystemProvisioner#downloadPackagesIntoContainer,
 * which handles nspawnmgr's own automatic RDP/VNC/desktop-manager package pre-fetch. Real
 * implementation goes over SSH as the sudo-capable account; the dev profile swaps in a fake that
 * does plain local filesystem I/O.
 */
public interface PackageCacheFilesystem {

    /**
     * Streams arbitrary binary content (a .deb/.rpm/.iso/etc., possibly several GB) to {@code
     * targetPath} - takes an already-open {@code InputStream} rather than a {@code byte[]}
     * deliberately: a file this large must never be fully buffered in memory anywhere along the
     * way (confirmed live: the previous byte[]-based signature caused a real OutOfMemoryError on a
     * large real upload). The caller owns opening and closing {@code content}.
     */
    void upload(String targetPath, InputStream content);

    void delete(String path);

    /** Copies the file at {@code sourcePath} into {@code destDir} (created if missing), keeping its
     *  filename. SYSTEMD_NSPAWN only - a plain host-side copy onto the machine's own host-visible
     *  rootfs directory. See {@link #copyIntoPodmanContainer} for the PODMAN equivalent, which
     *  needs the container name instead (podman's own storage has no equivalent host-visible path
     *  to copy into directly). */
    void copyIntoContainer(String sourcePath, String destDir);

    /** As {@link #copyIntoContainer}, for PODMAN - {@code podman cp}'s target addressing is
     *  {@code <container>:<path>}, not a plain host directory, so this takes the container name
     *  and the full destination path inside it (not just a directory - {@code podman cp} doesn't
     *  preserve the source filename the way the host-side copy does). */
    void copyIntoPodmanContainer(String sourcePath, String containerName, String destPathInContainer);

    /**
     * Lists every file already present in {@code dir} - read-only, used by
     * PackageCacheService#listAutoFetched to show what's sitting in a package manager's shared
     * "auto" cache directory without ever persisting that list anywhere (generated fresh on every
     * call). Best-effort: an inaccessible or missing directory returns an empty list rather than
     * throwing, since this is purely informational, same posture as
     * ContainerFilesystemProvisioner#listAvailableSourceFiles.
     */
    List<DownloadedPackage> listFiles(String dir);
}
