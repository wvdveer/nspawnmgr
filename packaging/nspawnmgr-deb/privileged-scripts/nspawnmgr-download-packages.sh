#!/bin/sh
# Downloads (with full dependency resolution) the given package names for a container's rootfs.
# Two paths depending on whether the HOST itself has apt-get:
#
# - Host has apt-get (a Debian/Ubuntu host): use the HOST's own network, same
#   -o Dir=/APT::Sandbox::User=root trick nspawnmgr-create-debian-template.sh already uses,
#   because apt/DNS resolution issued from *inside* a running systemd-nspawn container has been
#   confirmed unreliable on at least one real host, even when the host's own network/DNS works
#   fine.
# - Host has no apt-get (e.g. a Fedora/Arch host provisioning a Debian container - confirmed live
#   on a real Fedora 43 host, packaging/nspawnmgr-rpm/ verification): chroot into $ROOTFS itself
#   (which IS the Debian container being created, so it already has real apt/dpkg) rather than
#   `systemd-run --machine=` into a *live/booted* container - a bare chroot shares the host's own
#   network namespace/resolv.conf directly, so it does NOT reintroduce the live-container DNS
#   unreliability above. Same "chroot into a rootfs that has the right tool" technique
#   nspawnmgr-create-debian-template.sh/nspawnmgr-create-fedora-template.sh/
#   nspawnmgr-bootstrap-app-machine.sh's own JRE install already use elsewhere.
#
# Either way: --download-only: never touches the live container's dpkg/postinst/systemd - that
# stays the caller's own in-container install step's job, so package postinst scripts still run
# with a real init/dbus behind them exactly as before.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>)
# $2 = auto-cache dir (e.g. /var/cache/nspawnmgr/packages/apt/auto) - shared across every
#      container/install, so apt's own Dir::Cache::Archives caching means an already-cached,
#      still-valid .deb is never re-fetched.
# $3 = admin package cache's "uploaded" dir for apt (e.g.
#      /var/cache/nspawnmgr/packages/apt/uploaded) - PackageCacheService's own convention, see
#      below.
# $4.. = package names to fetch
set -e
ROOTFS="$1"
CACHE_DIR="$2"
UPLOAD_DIR="$3"
shift 3
mkdir -p "$CACHE_DIR" "$UPLOAD_DIR"
export DEBIAN_FRONTEND=noninteractive

if command -v apt-get >/dev/null 2>&1; then
    APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o Dir::Cache::Archives=$CACHE_DIR"
    apt-get $APT_OPTS update
    apt-get $APT_OPTS install --download-only -y "$@"
    # Copy (not move) into this container's own apt archive dir only the .deb files actually part of
    # *this* install's resolved dependency closure, so its later in-container `apt-get install`
    # (already running via systemd-run, WITHOUT its own `apt-get update` - see TemplateService's
    # default install commands) finds everything it needs locally and never touches the network
    # itself. Deliberately NOT a blanket `cp *.deb` from CACHE_DIR: that directory is shared and
    # accumulates .debs across every container/install ever run on this host, so a blanket copy would
    # blindly pull in unrelated leftovers (e.g. a gnome/kde container's .debs when this one only
    # needed openssh-server) into every single container's own archive dir.
    #
    # `--print-uris` was tried here first and is WRONG for this: it only lists files apt still needs
    # to *fetch*, so run immediately after `--download-only` just filled CACHE_DIR, everything already
    # looks satisfied and it prints nothing - confirmed live, a desktop-manager container ("b3", xfce4)
    # ended up with essentially none of its ~450 dependency .debs copied, and its later in-container
    # install then tried (and failed, no network) to fetch every single one itself. `install -s`
    # (simulate) asks the right question instead - "what's part of this transaction", not "what needs
    # downloading" - and always lists every package via `Inst <name> (<version> ...)` lines regardless
    # of cache state. Globbing "$CACHE_DIR/<name>_*.deb" per package name is safe because dpkg's own
    # naming convention (<name>_<version>_<arch>.deb) always delimits the name with an underscore
    # immediately after it, so e.g. "xfce4_*.deb" can never accidentally match "xfce4-goodies_*.deb".
    mkdir -p "$ROOTFS/var/cache/apt/archives"
    apt-get $APT_OPTS install -s -y "$@" 2>/dev/null | awk '/^Inst / {print $2}' | while IFS= read -r pkgname; do
        for f in "$CACHE_DIR/$pkgname"_*.deb; do
            [ -e "$f" ] && cp -n "$f" "$ROOTFS/var/cache/apt/archives/"
        done
    done

    # Also fetch just the explicitly-requested top-level package(s) on their own into a scratch dir and
    # register them in nspawnmgr's own admin package cache (/admin/packages) - confirmed live, that page
    # had no visibility into anything this auto-fetch path downloaded, even though the packages were
    # sitting on disk the whole time. Deliberately only the top-level package(s), not the (possibly
    # hundreds of) transitive dependency .debs already handled above via --download-only: those stay a
    # cache-directory implementation detail, since listing every one of a desktop environment's
    # dependencies individually in that picker would be noise, not a usable feature.
    #
    # `apt-get download` (unlike `install --download-only`) fetches exactly one predictably-named .deb
    # per package with no dependency closure mixed in, but only into the current working directory - it
    # doesn't honour -o Dir::Cache::Archives= the way `install` does, hence the explicit `cd`.
    SCRATCH="$(mktemp -d)"
    trap 'rm -rf "$SCRATCH"' EXIT
    for pkg in "$@"; do
        (cd "$SCRATCH" && apt-get $APT_OPTS download "$pkg" >/dev/null)
    done
    for f in "$SCRATCH"/*.deb; do
        [ -e "$f" ] || continue
        dest="$UPLOAD_DIR/auto-$(basename "$f")"
        cp -n "$f" "$dest"
        echo "$(basename "$f") $(wc -c < "$f")"
    done
else
    # Chroot fallback - see this file's own top-of-file comment for why chroot (not a live/booted
    # container) and why this is safe DNS-wise. $ROOTFS IS the Debian container being created, so
    # apt/dpkg already exist inside it - no separate helper rootfs needed. Mirrors
    # nspawnmgr-download-packages-dnf.sh's own already-live-verified shape closely: only CACHE_DIR
    # gets bind-mounted in (as apt's own -o Dir::Cache::Archives=, so the actual payload download
    # writes straight into nspawnmgr's persistent shared cache, not somewhere inside the container's
    # own disposable rootfs) - UPLOAD_DIR is never mounted at all, since every step that touches it
    # runs after the chroot is torn down, against plain host paths.
    IN_CONTAINER_CACHEDIR=/nspawnmgr-shared-cache
    HOST_BIND_TARGET="$ROOTFS$IN_CONTAINER_CACHEDIR"
    mkdir -p "$ROOTFS/run" "$HOST_BIND_TARGET"
    cleanup() {
        umount "$HOST_BIND_TARGET" 2>/dev/null || true
        umount "$ROOTFS/sys" 2>/dev/null || true
        umount "$ROOTFS/proc" 2>/dev/null || true
        umount "$ROOTFS/dev" 2>/dev/null || true
        umount "$ROOTFS/run" 2>/dev/null || true
    }
    trap cleanup EXIT
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    mount --bind /run "$ROOTFS/run"
    mount --bind /dev "$ROOTFS/dev"
    mount -t proc proc "$ROOTFS/proc"
    mount -t sysfs sys "$ROOTFS/sys"
    mount --bind "$CACHE_DIR" "$HOST_BIND_TARGET"

    chroot "$ROOTFS" apt-get update

    # Determine phase: same Inst-line parsing the host branch's own install -s does, just without
    # needing -o Dir::State::status= (chroot's own /var/lib/dpkg/status IS
    # $ROOTFS/var/lib/dpkg/status already). Captured into a variable (not piped straight into a loop)
    # so it's still available after the chroot is torn down below.
    NEEDED_NAMES="$(chroot "$ROOTFS" apt-get install -s -y "$@" 2>/dev/null | awk '/^Inst / {print $2}')"

    # Download phase: the one step that actually touches the network for package payloads, writing
    # straight into the shared cache via the bind mount above.
    chroot "$ROOTFS" apt-get -o Dir::Cache::Archives="$IN_CONTAINER_CACHEDIR" install --download-only -y "$@"

    cleanup
    trap - EXIT

    # Same copy-into-this-container's-own-archive-dir and top-level-package-registration steps as the
    # host branch above, operating on CACHE_DIR/UPLOAD_DIR as plain host paths now that the chroot is
    # gone - see that branch's own comments for the full reasoning.
    mkdir -p "$ROOTFS/var/cache/apt/archives"
    for pkgname in $NEEDED_NAMES; do
        for f in "$CACHE_DIR/$pkgname"_*.deb; do
            [ -e "$f" ] && cp -n "$f" "$ROOTFS/var/cache/apt/archives/"
        done
    done
    for pkg in "$@"; do
        for f in "$CACHE_DIR/$pkg"_*.deb; do
            [ -e "$f" ] || continue
            dest="$UPLOAD_DIR/auto-$(basename "$f")"
            cp -n "$f" "$dest"
            echo "$(basename "$f") $(wc -c < "$f")"
        done
    done
fi
