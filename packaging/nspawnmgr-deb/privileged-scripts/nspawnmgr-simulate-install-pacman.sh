#!/bin/sh
# PACMAN analogue of nspawnmgr-simulate-install.sh/-dnf.sh: determines which of an uploaded local
# .pkg.tar.*'s dependencies aren't already satisfied on the target container, without installing
# anything. Run via a HOST-side chroot into the container's rootfs - NOT `systemd-run --machine=`
# (in-container execution) - same conversion nspawnmgr-download-packages-pacman.sh already got.
# Confirmed live: a real provisioning run died mid-download with "Operation too slow" issued from
# inside a container's own network namespace, even with working host-level network/DNS - this
# script's own `pacman -Sy` call hits the network exactly the same way, and was a live, unconverted
# twin of that bug until now.
#
# The uploaded package itself lives in the host-side admin package cache, not inside the container's
# own rootfs, so it's copied in to a scratch location first and referenced by its in-rootfs path
# (chroot makes "in-container path" and "path relative to $ROOTFS" the same thing) from there on.
#
# Offline-first pass ported from the DNF/APT analogues (see nspawnmgr-simulate-install-dnf.sh's own
# header for the full rationale behind the general approach) with ONE deliberate difference: it does
# NOT parse `pacman -U`'s own dependency-failure output the way the DNF/APT ports parse "nothing
# provides"/"Depends: ... not installable" - that format has never been exercised anywhere in this
# project (this script's pre-existing "genuinely more speculative... never exercised, not even
# manually" caveat below still applies), and there is no live Arch environment in this dev
# environment to confirm it against. Instead this walks pacman -Qip's own "Depends On" field
# recursively (well-documented, stable pacman(8) behavior, not scraped from a resolver's own
# human-readable failure text) and pacman -T (deptest) to skip anything already satisfiable, which
# together need no unverified error-message parsing at all. UNVERIFIED LIVE END TO END - unlike the
# DNF port (extensively verified on acer's fedora-runner) and the APT port (verified against a real
# fake-root apt-get simulation), this whole script needs a real Arch container to confirm against
# before being fully trusted; test there first if one becomes available.
#
# Genuinely more speculative than the DNF analogue even past this conversion: pacman's local-file
# (-U) --print semantics for a full dependency-closure dry run have never been exercised anywhere in
# this project, not even manually - built to the documented pacman(8) contract as carefully as
# possible.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>) - no longer
#      needs the container's own machinectl/systemd-nspawn name at all.
# $2 = host-visible path to the uploaded .pkg.tar.* to simulate installing (NOT inside $1 - it lives
#      in the admin package cache)
# $3 = shared, persistent "auto" package-manager cache dir (e.g.
#      /var/cache/nspawnmgr/packages/pacman/auto) - same convention
#      nspawnmgr-download-packages-pacman.sh already uses for its own $2.
# $4 = admin package cache's "uploaded" dir for pacman (e.g.
#      /var/cache/nspawnmgr/packages/pacman/uploaded) - PackageCacheService's own convention.
set -e
ROOTFS="$1"
PKG_PATH="$2"
CACHE_DIR="$3"
UPLOAD_DIR="$4"
mkdir -p "$CACHE_DIR" "$UPLOAD_DIR"

IN_CONTAINER_SCRATCH=/var/cache/nspawnmgr-simulate
HOST_SCRATCH="$ROOTFS$IN_CONTAINER_SCRATCH"
rm -rf "$HOST_SCRATCH"
mkdir -p "$HOST_SCRATCH"
cp "$PKG_PATH" "$HOST_SCRATCH/"
IN_CONTAINER_PKG="$IN_CONTAINER_SCRATCH/$(basename "$PKG_PATH")"

# Same convention nspawnmgr-simulate-install-dnf.sh uses for its own cache bind mounts - two separate
# mount points since this script needs both the auto and uploaded dirs visible at once.
IN_CONTAINER_CACHEDIR=/var/cache/nspawnmgr-shared-cache
IN_CONTAINER_UPLOADDIR=/var/cache/nspawnmgr-shared-uploaded
HOST_CACHE_TARGET="$ROOTFS$IN_CONTAINER_CACHEDIR"
HOST_UPLOAD_TARGET="$ROOTFS$IN_CONTAINER_UPLOADDIR"
mkdir -p "$HOST_CACHE_TARGET" "$HOST_UPLOAD_TARGET"

# Same chroot setup nspawnmgr-download-packages-pacman.sh uses - see that script's own comments for
# the full rationale of each mount/copy.
cleanup() {
    umount "$HOST_CACHE_TARGET" 2>/dev/null || true
    umount "$HOST_UPLOAD_TARGET" 2>/dev/null || true
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
    rm -rf "$HOST_SCRATCH"
    # QUEUE_FILE/NEXT_QUEUE_FILE are declared further down, only ever set to real mktemp paths once
    # the resolution loop actually runs - same deferred-reference reasoning as PROVIDES_MAP in the
    # DNF sibling: this still picks up whatever value they hold by the time cleanup fires, unset or
    # not, and cleans up early-failure leftovers the loop's own normal-path rm -f calls wouldn't reach.
    [ -n "$QUEUE_FILE" ] && rm -f "$QUEUE_FILE"
    [ -n "$NEXT_QUEUE_FILE" ] && rm -f "$NEXT_QUEUE_FILE"
    # `true` at the end matters, not cosmetic - confirmed live for the DNF sibling (acer,
    # 2026-09-08): under `set -e`, a false LAST command inside an EXIT trap silently overrides an
    # earlier explicit `exit 0`. Nothing above this point is guaranteed non-false on every run (an
    # umount can legitimately have nothing to unmount), so this is the unconditional safety net.
    true
}
trap cleanup EXIT
cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
mount --bind "$CACHE_DIR" "$HOST_CACHE_TARGET"
mount --bind "$UPLOAD_DIR" "$HOST_UPLOAD_TARGET"

# Clear whatever's already in the container's own real pacman cache before either resolution path
# below runs - same reasoning and same unconditional placement as the DNF/APT siblings' own fix:
# this script is only ever invoked once per "Install package" attempt, so this is the one safe,
# shared place to guarantee a clean slate regardless of which path this specific install ends up
# taking, without touching nspawnmgr-download-packages-pacman.sh's own accumulate-across-calls
# behavior that ContainerFilesystemProvisioner's own auto-installs may still rely on.
rm -f "$ROOTFS/var/cache/pacman/pkg"/*.pkg.tar.*

OWN_NAME="$(chroot "$ROOTFS" pacman -Qip "$IN_CONTAINER_PKG" 2>/dev/null | awk -F': *' '/^Name/ {print $2; exit}')"

# Strips a version constraint (e.g. "foo>=1.2" -> "foo") from a dependency spec, leaving a bare name
# suitable for both `pacman -T` and the filename-glob lookup below.
bare_name() {
    printf '%s\n' "$1" | sed -E 's/[<>=].*$//'
}

# Prints a package file's own "Depends On" field as one dependency spec per line (empty if none) -
# pacman -Qip's stable, documented output, not anything scraped from a resolver's own human-readable
# failure text.
get_depends() {
    chroot "$ROOTFS" pacman -Qip "$1" 2>/dev/null | awk -F': *' '
        /^Depends On/ { deps=$2; next }
        END { if (deps != "" && deps != "None") { n=split(deps, a, " "); for (i=1;i<=n;i++) print a[i] }}
    '
}

NAMES_SEEN=" $OWN_NAME "
CACHE_PKGS=""
add_pkg_path() {
    case " $CACHE_PKGS " in
        *" $1 "*) return 0 ;;
    esac
    CACHE_PKGS="$CACHE_PKGS $1"
}
# Looks up cached files matching $1 (a bare package name) and, for each one actually found, queues
# its own "Depends On" specs too (via $2, a host-visible file to append newly-discovered dep specs
# into) - the recursive part of this resolution.
add_candidates_for_name() {
    for f in "$HOST_CACHE_TARGET/$1"-*.pkg.tar.* "$HOST_UPLOAD_TARGET/$1"-*.pkg.tar.*; do
        [ -e "$f" ] || continue
        case "$f" in
            "$HOST_CACHE_TARGET"/*) inpath="$IN_CONTAINER_CACHEDIR/$(basename "$f")" ;;
            *) inpath="$IN_CONTAINER_UPLOADDIR/$(basename "$f")" ;;
        esac
        add_pkg_path "$inpath"
        get_depends "$inpath" >> "$2"
    done
}

OFFLINE_RESOLVED=1
QUEUE_FILE="$(mktemp)"
get_depends "$IN_CONTAINER_PKG" > "$QUEUE_FILE"
i=0
while [ "$i" -lt 15 ]; do
    i=$((i + 1))
    QUEUE="$(cat "$QUEUE_FILE")"
    [ -n "$QUEUE" ] || break
    : > "$QUEUE_FILE"
    NEXT_QUEUE_FILE="$(mktemp)"
    for spec in $QUEUE; do
        name="$(bare_name "$spec")"
        case " $NAMES_SEEN " in
            *" $name "*) continue ;;
        esac
        NAMES_SEEN="$NAMES_SEEN $name"
        # -T (deptest) prints whichever of the given specs are NOT already satisfiable (installed or
        # provided) - documented, stable pacman(8) behavior. Only chase a candidate FILE for
        # something genuinely still missing; anything -T already considers satisfied (e.g. a base
        # system library) needs no local package at all.
        UNSATISFIED="$(chroot "$ROOTFS" pacman -T "$spec" 2>/dev/null || true)"
        [ -n "$UNSATISFIED" ] || continue
        BEFORE="$CACHE_PKGS"
        add_candidates_for_name "$name" "$NEXT_QUEUE_FILE"
        # `if`, not a bare `[ cond ] && cmd` - kept consistent with the rest of this fix pass even
        # though this specific form wasn't confirmed to reproduce in isolated testing: the real,
        # observed crash in the APT port's own structurally similar case (nested loop calling a
        # function) was concrete enough that relying on an incomplete understanding of exactly when
        # it triggers isn't worth the risk here.
        if [ "$CACHE_PKGS" = "$BEFORE" ]; then
            OFFLINE_RESOLVED=0
        fi
    done
    if [ "$OFFLINE_RESOLVED" -eq 0 ]; then
        rm -f "$NEXT_QUEUE_FILE"
        break
    fi
    mv "$NEXT_QUEUE_FILE" "$QUEUE_FILE"
done
if [ -s "$QUEUE_FILE" ]; then
    OFFLINE_RESOLVED=0
fi
rm -f "$QUEUE_FILE"

if [ "$OFFLINE_RESOLVED" -eq 1 ]; then
    # Every one of these resolved purely from a local file already sitting in $CACHE_DIR/
    # $UPLOAD_DIR - copy it into the container's own real pacman cache now (matching
    # nspawnmgr-download-packages-pacman.sh's own --cachedir= convention), since that script never
    # runs when this function reports zero missing dependencies below. Already cleared of any stale
    # leftovers up top.
    mkdir -p "$ROOTFS/var/cache/pacman/pkg"
    for path in $CACHE_PKGS; do
        base="$(basename "$path")"
        case "$path" in
            "$IN_CONTAINER_CACHEDIR"/*) src="$CACHE_DIR/$base" ;;
            *) src="$UPLOAD_DIR/$base" ;;
        esac
        [ -e "$src" ] || continue
        cp -n "$src" "$ROOTFS/var/cache/pacman/pkg/" 2>/dev/null || true
    done
    exit 0
fi

# Offline resolution above either found nothing to work with (empty cache) or couldn't fully resolve
# the transaction from local files alone - fall back to the original network-based simulate,
# unchanged from before this offline-first pass was added.
chroot "$ROOTFS" pacman --noconfirm -Sy >/dev/null
chroot "$ROOTFS" pacman --noconfirm -U --print --print-format '%n' "$IN_CONTAINER_PKG" 2>/dev/null | grep -vFx "$OWN_NAME" || true
