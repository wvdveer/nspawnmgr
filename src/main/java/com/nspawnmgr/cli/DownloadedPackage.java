package com.nspawnmgr.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * One top-level package {@link ContainerFilesystemProvisioner#downloadPackagesIntoContainer} fetched
 * host-side — deliberately just the explicitly-requested package itself (e.g. "xfce4"), not its
 * transitive dependency closure (also downloaded, but as a cache-directory implementation detail
 * only; showing every one of a desktop environment's hundreds of dependency .debs individually in
 * the admin package cache would be noise, not a usable feature - though {@link
 * com.nspawnmgr.service.PackageCacheService#listAutoFetched} does let an admin list that closure
 * on demand, without persisting it anywhere). The caller registers top-level ones with
 * PackageCacheService so they're visible/reusable from /admin/packages, not just a side effect
 * invisible outside this one provisioning run.
 */
public record DownloadedPackage(String filename, long sizeBytes) {

    /**
     * Parses "&lt;filename&gt; &lt;size-bytes&gt;" lines (one per file - see
     * nspawnmgr-download-packages.sh/-dnf.sh/-pacman.sh and nspawnmgr-list-auto-cache.sh's own
     * output convention) into records. Shared by every caller that shells out to one of those
     * scripts, rather than each re-implementing the same parse. Lines that don't match (stray
     * chatter that slipped onto stdout) are silently skipped rather than failing the whole call
     * over them.
     */
    public static List<DownloadedPackage> parseLines(String stdout) {
        List<DownloadedPackage> result = new ArrayList<>();
        for (String line : stdout.lines().toList()) {
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace <= 0) {
                continue;
            }
            String filename = line.substring(0, lastSpace);
            try {
                long sizeBytes = Long.parseLong(line.substring(lastSpace + 1).trim());
                result.add(new DownloadedPackage(filename, sizeBytes));
            } catch (NumberFormatException e) {
                // Not one of the expected output lines - ignore rather than fail the whole call.
            }
        }
        return result;
    }
}
