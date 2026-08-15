package com.nspawnmgr.domain;

import java.util.Arrays;

/**
 * Two different uses share this one enum: the package manager a {@link Template}'s base image uses
 * (so provisioning knows how to install sshd/xrdp) - APT/DNF/APK/PACMAN only, see
 * {@link #forTemplates()} - and, for {@link com.nspawnmgr.domain.CachedPackage}, a discriminator
 * for what kind of artifact is cached: any of those four for a real install-command-driven package,
 * or {@link #ISO}, a fundamentally different kind that's mounted onto a container (read-only, at
 * most one at a time - see Container.mountedIso) rather than installed.
 */
public enum PackageManager {
    APT, DNF, APK, PACMAN, ISO;

    /** Excludes ISO - a Template's own package manager is never "ISO", it drives real
     *  apt-get/dnf/apk/pacman install commands. Used for the Templates admin form's dropdown only. */
    public static PackageManager[] forTemplates() {
        return Arrays.stream(values()).filter(pm -> pm != ISO).toArray(PackageManager[]::new);
    }

    /**
     * Package managers whose local-install command doesn't already resolve missing dependencies on
     * its own, so PackageCacheService.installOnContainer pre-fetches them onto the host instead of
     * letting a container reach the network directly - and, by the same token, the only ones with a
     * {@code CACHE_ROOT/<manager>/auto} directory worth listing (see
     * PackageCacheService#listAutoFetched, the admin Packages page's "Show transitive dependencies"
     * button). APK is excluded: its local install actually would resolve dependencies from
     * configured repos on its own, but Alpine-based containers don't fully work in this app today
     * regardless. ISO isn't a real package manager for install purposes at all.
     */
    public static PackageManager[] forDependencyPreFetch() {
        return new PackageManager[] {APT, DNF, PACMAN};
    }
}
