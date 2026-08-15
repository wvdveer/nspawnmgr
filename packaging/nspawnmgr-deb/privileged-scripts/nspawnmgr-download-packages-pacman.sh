#!/bin/sh
# PACMAN analogue of nspawnmgr-download-packages.sh: downloads (with full dependency resolution) the
# given package names, using the target container's own bundled pacman via a HOST-side chroot into
# its rootfs (the host itself has no pacman binary at all - see nspawnmgr-create-arch-template.sh's
# own chroot fallback for the same reasoning) - but the container's rootfs is used ONLY to run
# pacman itself, never as where the downloaded bytes land. The actual payload download writes
# directly into nspawnmgr's own persistent, shared package cache ($CACHE_DIR, bind-mounted into the
# chroot as pacman's own --cachedir=) - pacman's native cache-skip behavior means a package another
# container already fetched here is reused directly, never re-downloaded. Real cross-container
# package reuse, by deliberate design - not the "IN_CONTAINER_DESTDIR" per-invocation scratch-dir
# approach an earlier version of this script used, which re-downloaded fresh on every single call.
#
# Confirmed live: downloads issued from *inside* a running systemd-nspawn container have been
# unreliable on at least one real host even when the host's own network/DNS works fine - the same
# class of issue nspawnmgr-download-packages.sh's own header comment already documents for APT (the
# whole reason that script runs on the host instead). A real provisioning run died mid-transfer on
# geo.mirror.pkgbuild.com with "Operation too slow. Less than 1 bytes/sec transferred" issued via
# systemd-run --machine= (i.e. from inside the container's own network namespace) - chrooting uses
# the HOST's own network stack directly instead of routing through the container's veth/bridge path.
#
# This container's rootfs is a HOST-visible path even while the container is live/running -
# systemd-nspawn's own bind-mounts for it (its private /proc, /dev, /sys, etc.) live inside the
# container's own mount namespace, invisible to the host's view of that same path - so mounting onto
# it here from the host side doesn't disturb the running container at all, the same reasoning every
# other host-side file write this app already does against a live container's rootfs already relies
# on (writing .nspawn settings, VNC xstartup/passwd, etc.).
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>) - no longer
#      needs the container's own machinectl/systemd-nspawn name at all, since this never enters any
#      of its namespaces.
# $2 = shared, persistent package-manager cache dir (e.g. /var/cache/nspawnmgr/packages/pacman/auto)
#      - now genuinely pacman's own --cachedir= for the real download, not just a post-hoc copy
#      destination; also what backs the admin Packages page's "Show transitive dependencies" viewer.
# $3 = admin package cache's "uploaded" dir for pacman (e.g.
#      /var/cache/nspawnmgr/packages/pacman/uploaded) - PackageCacheService's own convention.
# $4.. = package names to fetch
set -e
ROOTFS="$1"
CACHE_DIR="$2"
UPLOAD_DIR="$3"
shift 3
mkdir -p "$CACHE_DIR" "$UPLOAD_DIR"

IN_CONTAINER_CACHEDIR=/var/cache/nspawnmgr-shared-cache
HOST_BIND_TARGET="$ROOTFS$IN_CONTAINER_CACHEDIR"
mkdir -p "$HOST_BIND_TARGET"

# Same chroot setup nspawnmgr-create-arch-template.sh's own chroot fallback branch uses - see that
# script's own comments for the full rationale of each mount/copy (resolv.conf for DNS, /run
# specifically for nss-resolve's socket, /proc+/sys+/dev for pacman's own hooks). Plus one more bind
# mount of our own: the shared cache dir itself, so pacman's --cachedir= (a chroot-relative path)
# resolves to the real, persistent, host-side directory rather than somewhere inside the container's
# own disposable rootfs.
cleanup() {
    umount "$HOST_BIND_TARGET" 2>/dev/null || true
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
}
trap cleanup EXIT
cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
mount --bind "$CACHE_DIR" "$HOST_BIND_TARGET"

chroot "$ROOTFS" pacman --noconfirm -Sy

# Determine phase: -Sp prints each package's own download URL for the full resolved dependency
# closure without fetching any payload - pure metadata/resolution. The container's own pacman is
# used only to run this, per this script's own design (its rootfs never receives the actual bytes).
NEEDED_FILES="$(chroot "$ROOTFS" pacman --noconfirm -Sp "$@" | sed 's#.*/##')"

# Download phase: --cachedir points (via the bind mount above) at nspawnmgr's own persistent, shared
# package cache - this is the one step that actually touches the network for package payloads, and
# pacman itself skips anything already sitting there from an earlier container's own download.
chroot "$ROOTFS" pacman --noconfirm --cachedir="$IN_CONTAINER_CACHEDIR" -Sw "$@"

umount "$HOST_BIND_TARGET"
umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

# Copy from the shared cache into the container's own real pacman cache too, so its later real
# in-container install (see TemplateService's own PACMAN install commands) finds everything already
# sitting locally - a real, non-hashed path, confirmed live pacman finds and reuses it directly
# without any extra argument-wrangling on the install side.
mkdir -p "$ROOTFS/var/cache/pacman/pkg"
echo "$NEEDED_FILES" | while IFS= read -r f; do
    [ -n "$f" ] && [ -e "$CACHE_DIR/$f" ] && cp -n "$CACHE_DIR/$f" "$ROOTFS/var/cache/pacman/pkg/"
done

# Register just the explicitly-requested top-level package(s) (not their transitive dependencies,
# which stay a cache-directory implementation detail) in the admin package cache proper. The shared
# cache dir itself (already populated by the download phase above, and visible to every other
# container/install on this host) already backs the admin Packages page's "Show transitive
# dependencies" viewer directly - no separate copy step needed for that part anymore.
for pkg in "$@"; do
    for f in "$CACHE_DIR/$pkg"-*.pkg.tar.*; do
        [ -e "$f" ] || continue
        dest="$UPLOAD_DIR/auto-$(basename "$f")"
        cp -n "$f" "$dest"
        echo "$(basename "$f") $(wc -c < "$f")"
    done
done
