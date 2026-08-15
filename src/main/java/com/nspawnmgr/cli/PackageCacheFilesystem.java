package com.nspawnmgr.cli;

import java.util.List;

/**
 * Host-side filesystem operations backing the admin-curated package upload cache
 * (PackageCacheService) - distinct from ContainerFilesystemProvisioner#downloadPackagesIntoContainer,
 * which handles nspawnmgr's own automatic RDP/VNC/desktop-manager package pre-fetch. Real
 * implementation goes over SSH as the sudo-capable account; the dev profile swaps in a fake that
 * does plain local filesystem I/O.
 */
public interface PackageCacheFilesystem {

    /** Writes arbitrary binary content (a .deb/.rpm/etc.) to {@code targetPath}. */
    void upload(String targetPath, byte[] content);

    void delete(String path);

    /** Copies the file at {@code sourcePath} into {@code destDir} (created if missing), keeping its filename. */
    void copyIntoContainer(String sourcePath, String destDir);

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
