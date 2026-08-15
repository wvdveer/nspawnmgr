#!/bin/sh
# DNF analogue of nspawnmgr-download-packages-pacman.sh: downloads (with full dependency resolution)
# the given package names, using the target container's own bundled dnf via a HOST-side chroot into
# its rootfs (the host itself has no dnf binary at all - see nspawnmgr-create-fedora-template.sh's
# own chroot fallback for the same reasoning) - but the container's rootfs is used ONLY to run dnf
# itself, never as where the downloaded bytes land. The actual payload download writes directly into
# nspawnmgr's own persistent, shared package cache ($CACHE_DIR, bind-mounted into the chroot as
# dnf's own --destdir=) - matching the PACMAN sibling's own design so a package another container
# already fetched here is available for the copy-in step below without a fresh network hit.
#
# UNVERIFIED specifically for this part: unlike pacman's own well-established --cachedir cache-skip
# behavior (confirmed live for the PACMAN sibling), whether dnf5's own `download --destdir=` skips
# re-fetching a file that already exists there hasn't been confirmed either way - if it always
# re-fetches regardless, this still isn't a regression (the shared dir still ends up with the file,
# still avoids a *second* copy for other containers' own copy-in step below), just without the
# network-avoidance win PACMAN gets automatically. Flag live if this turns out to matter.
#
# Confirmed live: downloads issued from *inside* a running systemd-nspawn container have been
# unreliable on at least one real host even when the host's own network/DNS works fine - the same
# class of issue nspawnmgr-download-packages.sh's own header comment already documents for APT (the
# whole reason that script runs on the host instead), and confirmed for PACMAN too (a real
# provisioning run died mid-transfer on geo.mirror.pkgbuild.com with "Operation too slow"). Chrooting
# uses the HOST's own network stack directly instead of routing through the container's veth/bridge
# path.
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
# $2 = shared, persistent package-manager cache dir (e.g. /var/cache/nspawnmgr/packages/dnf/auto) -
#      now genuinely dnf's own --destdir= for the real download, not just a post-hoc copy
#      destination; also what backs the admin Packages page's "Show transitive dependencies" viewer.
# $3 = admin package cache's "uploaded" dir for dnf (e.g.
#      /var/cache/nspawnmgr/packages/dnf/uploaded) - PackageCacheService's own convention.
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

# Same chroot setup nspawnmgr-create-fedora-template.sh's own chroot fallback branch uses - see that
# script's own comments for the full rationale of each mount/copy. Plus one more bind mount of our
# own: the shared cache dir itself, so dnf's --destdir= (a chroot-relative path) resolves to the
# real, persistent, host-side directory rather than somewhere inside the container's own disposable
# rootfs. No --releasever= here (unlike the bake script) - this container's own dnf already knows its
# own release from its already-installed os-release/repo config.
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

chroot "$ROOTFS" dnf --assumeyes makecache

# Determine phase: an --assumeno dry-run transaction lists every package (full resolved dependency
# closure, not just the top-level name(s)) that WOULD be installed, without installing or downloading
# anything - pure metadata/resolution. Same output-parsing awk nspawnmgr-simulate-install-dnf.sh's own
# missing-dependency check already uses. The container's own dnf is used only to run this, per this
# script's own design (its rootfs never receives the actual bytes).
NEEDED_NAMES="$(chroot "$ROOTFS" dnf --assumeyes install --assumeno "$@" 2>/dev/null | awk '
    /^Installing/ { active=1; next }
    /^Transaction Summary/ { active=0 }
    active && NF >= 4 && $1 !~ /^=+$/ { print $1 }
' || true)"

# Download phase: --destdir points (via the bind mount above) at nspawnmgr's own persistent, shared
# package cache - this is the one step that actually touches the network for package payloads.
# Confirmed live: dnf5 rejects --destdir on `install` outright ("Unknown argument \"--destdir=...\"
# for command \"install\" ... available for: reposync, download, upgrade") - dnf4's
# `install --downloadonly --destdir=` combo doesn't carry over. dnf5's own download-without-
# installing command is `download`, and by default it fetches only the named package(s), not their
# dependencies - `--resolve` is what pulls in the full dependency closure too.
chroot "$ROOTFS" dnf --assumeyes download --resolve --destdir="$IN_CONTAINER_CACHEDIR" "$@"

umount "$HOST_BIND_TARGET"
umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

# Leave a copy in the container's own dnf cache too, so its later real in-container install (see
# TemplateService's own DEFAULT_INSTALL_SSH/XRDP/VNC/DESKTOP entries) finds everything already
# sitting locally without needing dnf5's real, dynamically-hashed /var/cache/libdnf5/<repo>-<hash>/
# cache to already know about them - those install commands glob this exact directory as explicit
# local-file install arguments instead of relying on dnf's own cache lookup to find them.
mkdir -p "$ROOTFS/var/cache/dnf"
for name in $NEEDED_NAMES; do
    for f in "$CACHE_DIR/$name"-*.rpm; do
        [ -e "$f" ] && cp -n "$f" "$ROOTFS/var/cache/dnf/"
    done
done

# Register just the explicitly-requested top-level package(s) (not their transitive dependencies,
# which stay a cache-directory implementation detail) in the admin package cache proper. The shared
# cache dir itself (already populated by the download phase above, and visible to every other
# container/install on this host) already backs the admin Packages page's "Show transitive
# dependencies" viewer directly - no separate copy step needed for that part anymore.
for pkg in "$@"; do
    for f in "$CACHE_DIR/$pkg"-*.rpm; do
        [ -e "$f" ] || continue
        dest="$UPLOAD_DIR/auto-$(basename "$f")"
        cp -n "$f" "$dest"
        echo "$(basename "$f") $(wc -c < "$f")"
    done
done
