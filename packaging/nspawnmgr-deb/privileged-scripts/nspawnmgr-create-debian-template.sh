#!/bin/sh
# Downloads a Debian bookworm minirootfs from images.linuxcontainers.org, extracts it, and bakes
# openssh-server into it (systemd itself is already present in Debian's own rootfs, unlike
# Alpine's OpenRC-based image - see the removed nspawnmgr-create-alpine-template.sh's own comment
# for why that made Alpine fundamentally incompatible with this app's systemd-run --machine=
# -based container management: "Failed to connect to bus" forever, not a transient boot race).
#
# If the HOST itself is Debian/Ubuntu-based (has apt-get), installs it as a normal host process
# pointed at the extracted rootfs - the confirmed-live, proven-fast path. Otherwise (nspawnmgr is
# deployed on some other distro entirely - Fedora, Arch, ...), falls back to chrooting into the
# rootfs and using ITS OWN bundled apt-get instead, the same "package manager in container"
# technique nspawnmgr-create-fedora-template.sh/-arch-template.sh always fall back to - there's
# nothing else to use when the host has no matching package manager at all. UNVERIFIED: every real
# confirmation of this script baking a working template happened on a Debian-family host, taking
# the host-side branch below - the chroot fallback has never been exercised live.
#
# $1 = target .tar.gz path (must not already exist) - a machinectl import-tar-compatible gzipped
# tar, not a live directory: nothing needs to traverse into a template once it's a single file, so
# there's no permission-wrangling needed for whoever manages TEMPLATES_DIR afterwards.
#
# Requires a sudo password (see ContainerFilesystemProvisioner.createDebianMinimalTemplate) —
# creation-time-only, invoked from the Templates admin page's "Set up debian-minimal" button (only
# shown when no templates exist yet).
set -e

TARGET="$1"
ARCH="$(uname -m)"
# images.linuxcontainers.org names architectures its own way (amd64/arm64), not uname -m's
# (x86_64/aarch64).
case "$ARCH" in
    x86_64) ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
esac
DEBIAN_RELEASE="bookworm"
INDEX_URL="https://images.linuxcontainers.org/images/debian/${DEBIAN_RELEASE}/${ARCH}/default/"

if [ -e "$TARGET" ]; then
    echo "Target file already exists" >&2
    exit 1
fi

for cmd in curl tar sha256sum chroot mount umount; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

# Resolve the newest build actually published there right now, same reasoning as
# tools/scripts/setup-container-template.sh: images.linuxcontainers.org prunes older timestamped
# builds over time, so pinning one would need periodic bumping to keep working.
LATEST_BUILD="$(curl -fsSL "$INDEX_URL" \
    | grep -oE 'href="[0-9]{8}_[0-9]{2}%3A[0-9]{2}/"' \
    | sed -E 's/href="(.*)\/"/\1/' \
    | sort | tail -1)"
if [ -z "$LATEST_BUILD" ]; then
    echo "Couldn't find a build under $INDEX_URL - the image server's layout may have changed." >&2
    exit 1
fi
BUILD_URL="${INDEX_URL}${LATEST_BUILD}/"

WORK_DIR="$(mktemp -d)"
ROOTFS="$WORK_DIR/rootfs"
# Unmount before the work dir gets removed - rm -rf on a directory with active bind mounts inside
# it would either fail or (worse) delete the mount point out from under an active mount. `|| true`
# on each: if the script fails before a given mount was even set up (or the host-side branch below
# never mounted anything at all), that umount is a harmless no-op, not a real error.
cleanup() {
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "Downloading rootfs from $BUILD_URL..."
curl -fsSL -o "$WORK_DIR/rootfs.tar.xz" "${BUILD_URL}rootfs.tar.xz"
EXPECTED="$(curl -fsSL "${BUILD_URL}SHA256SUMS" | awk '$2 == "rootfs.tar.xz" || $2 == "*rootfs.tar.xz" {print $1}')"
if [ -z "$EXPECTED" ]; then
    echo "Couldn't find rootfs.tar.xz's checksum in ${BUILD_URL}SHA256SUMS" >&2
    exit 1
fi
ACTUAL="$(sha256sum "$WORK_DIR/rootfs.tar.xz" | awk '{print $1}')"
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "Checksum mismatch for rootfs.tar.xz — expected $EXPECTED, got $ACTUAL. Aborting." >&2
    exit 1
fi

ROOTFS="$WORK_DIR/rootfs"
mkdir -p "$ROOTFS"
tar -xpf "$WORK_DIR/rootfs.tar.xz" -C "$ROOTFS"

# A freshly-extracted rootfs tarball has no /dev entries (device nodes aren't portable inside a
# plain tar archive). Belt-and-braces for the FINAL packed template either way (systemd-nspawn sets
# up its own private /dev at container boot regardless of what's baked in here) - the host-side
# branch below additionally needs these to exist upfront for openssh-server's postinst to run at
# all (confirmed live: "Can't open /dev/null: No such file or directory" without this); the chroot
# branch gets a real, fully populated /dev via its own bind mount instead.
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
mknod -m 666 "$ROOTFS/dev/null" c 1 3
mknod -m 666 "$ROOTFS/dev/zero" c 1 5
mknod -m 666 "$ROOTFS/dev/random" c 1 8
mknod -m 666 "$ROOTFS/dev/urandom" c 1 9
mknod -m 666 "$ROOTFS/dev/tty" c 5 0

# Set unconditionally (not just inside the host-side branch below) so it's inherited by the
# chroot'd apt-get too if that branch runs instead - export propagates to any child process,
# chroot included, without needing an explicit `env` prefix.
export DEBIAN_FRONTEND=noninteractive

echo "Installing openssh-server into the template..."
if command -v apt-get >/dev/null 2>&1; then
    # Host is Debian/Ubuntu-based - confirmed live, run apt-get as a normal HOST process pointed at
    # $ROOTFS. Deliberately NOT `systemd-nspawn -D "$ROOTFS" ... apt-get ...`: that runs apt's
    # network traffic (including DNS resolution) from inside the container's mount/network setup,
    # and that's confirmed live to be unreliable - apt-get update failed with "Temporary failure
    # resolving 'deb.debian.org'" even though this same host's own DNS works fine and the rootfs
    # download above (real curl, run directly on the host) succeeded moments earlier. Instead,
    # point apt's Dir at $ROOTFS directly: apt-get then runs as an ordinary host process - using the
    # host's own working network/DNS - and reads $ROOTFS's sources.list, resolves+downloads .deb
    # files, then unpacks/configures them into $ROOTFS (dpkg chroots into $ROOTFS only for that
    # last step, same as it always does for a non-root-directory install). Downloading happens
    # outside the container; only the already-fetched files get transferred in.
    #
    # apt's official --root= flag turned out to be unusable here: confirmed live, this host's apt
    # (2.8.3, Linux Mint 22.1) rejects it outright with "Command line option --root=... is not
    # understood in combination with the other options" - reproduced even as genuine root, with
    # --root as the ONLY option, and with plain `apt` instead of `apt-get`, so it's not a
    # flag-combination or privilege issue, apt on this host just doesn't accept it. Falling back to
    # a manual -o Dir=/-o Dir::State::status= combo (apt's own package resolution) PLUS
    # -o DPkg::Options::=--root= (forces the dpkg *subprocess* apt spawns to also chroot there for
    # unpack/configure - the piece a plain -o Dir= combo is missing on its own). Without that
    # DPkg::Options push-through, dpkg validated downloaded Debian bookworm packages against the
    # HOST's own (Ubuntu) dpkg database instead of $ROOTFS's - "dpkg: dependency problems ...
    # ncurses-base (6.4+20240113-1ubuntu2) breaks ncurses-term" was the host's own installed Ubuntu
    # package colliding with the bookworm one being unpacked into $ROOTFS.
    #
    # -o APT::Sandbox::User=root: confirmed live, without this apt drops privileges to the
    # unprivileged '_apt' user for the actual download step (its usual security sandboxing) - which
    # fails with "couldn't be accessed by user '_apt' ... Permission denied" because
    # $ROOTFS/var/lib/apt/lists and $ROOTFS/var/cache/apt/archives are only writable by root, having
    # just been extracted from the rootfs tarball as-is. That sandboxing exists to protect the
    # host's real root filesystem from a malicious/compromised download; it's not needed here since
    # this whole script already runs as root building a throwaway working directory, not installing
    # onto the live system.
    APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$ROOTFS"
    apt-get $APT_OPTS update
    apt-get $APT_OPTS install -y openssh-server
else
    # Host has no apt-get at all (nspawnmgr deployed on a Fedora or Arch host, say) - chroot into
    # the rootfs and use ITS OWN bundled apt-get/dpkg instead, the same fallback
    # nspawnmgr-create-fedora-template.sh/-arch-template.sh use for their own "foreign host" case.
    # chroot doesn't share the host's network config automatically - apt needs $ROOTFS's own
    # resolv.conf to resolve Debian's mirrors. /proc and /sys get their own fresh mounts (not bind
    # mounts of the host's) - dpkg's own postinst scripts (openssh-server's included) can expect
    # them to exist. These three unmount again right after the install, deliberately NOT left
    # mounted until the trap-based cleanup at script exit - packing the rootfs into $TARGET further
    # down must never capture a live snapshot of the host's own /proc or /dev.
    # --remove-destination: some minimal base images ship /etc/resolv.conf as a symlink to the
    # absolute path "/etc/resolv.conf" itself (a placeholder meant to be replaced by whatever
    # container runtime boots it, confirmed live for the Fedora image) - outside a chroot that
    # resolves right back to the HOST's own real /etc/resolv.conf, so a plain `cp` sees source and
    # destination as the same inode and refuses with "are the same file". --remove-destination
    # unlinks the destination path first, so cp never has to open-and-follow that symlink at all.
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    # /run bind mount: confirmed live for the Arch script's own chroot branch hitting this exact
    # issue ("Could not resolve host"), applied here defensively too, in case a foreign host's
    # Debian/Ubuntu image ever ships with an nss-resolve-first nsswitch.conf. nss-resolve doesn't
    # read /etc/resolv.conf at all, it talks to systemd-resolved over the socket it publishes under
    # /run - unreachable from an untouched, freshly-extracted chroot /run. Real
    # `debootstrap`-adjacent bootstrap tooling bind-mounts /run for exactly this reason.
    mkdir -p "$ROOTFS/run"
    mount --bind /run "$ROOTFS/run"
    mount --bind /dev "$ROOTFS/dev"
    mount -t proc proc "$ROOTFS/proc"
    mount -t sysfs sys "$ROOTFS/sys"
    chroot "$ROOTFS" apt-get update
    chroot "$ROOTFS" apt-get install -y openssh-server
    umount "$ROOTFS/sys"
    umount "$ROOTFS/proc"
    umount "$ROOTFS/dev"
    umount "$ROOTFS/run"
    # The resolv.conf copy above is build-time-only glue for apt to reach Debian's mirrors from
    # inside this chroot - it must not survive into the packed template. Left in place, every future
    # container booted from it would ship with a real (non-symlink) /etc/resolv.conf frozen to
    # whatever this BUILD host's own resolver happened to be at bake time: systemd's own postinst
    # only ever creates the /etc/resolv.conf -> stub-resolv.conf symlink once, at package-install
    # time, so nothing re-creates it at container boot, permanently locking out
    # systemd-resolved's live per-link management - including the Domains=internal drop-in below,
    # since resolved never gets a chance to actually own the file. Restore the standard
    # systemd-resolved-managed symlink so real containers dynamically resolve via their own live
    # network config again, same as the host-apt branch's untouched rootfs already does.
    ln -sf ../run/systemd/resolve/stub-resolv.conf "$ROOTFS/etc/resolv.conf"
fi

# Without net.ipv4.ping_group_range set, ping from inside the container fails - confirmed live,
# container-to-container ping tests failing even once name resolution itself worked. The package
# that normally sets this (linux-sysctl-defaults) isn't available for bookworm outside
# backports/trixie+ though (confirmed live: apt-get install fails with "Unable to locate package"
# - that's what broke the "Set up debian-minimal" button), so write the one line it would have
# installed directly instead of depending on a package that doesn't exist for this release.
# "0 2147483647" (the usual "no limit" idiom) fails with EINVAL when written from inside a
# systemd-nspawn container - confirmed live, the container's own user-namespace only maps a
# 65536-wide GID range, so the kernel rejects anything wider and silently keeps its own default
# (65534 65534, i.e. ping still broken) instead. "0 65535" fits within that mapped range.
mkdir -p "$ROOTFS/etc/sysctl.d"
echo "net.ipv4.ping_group_range = 0 65535" > "$ROOTFS/etc/sysctl.d/50-ping-group-range.conf"
# Without a routing/search domain, systemd-resolved inside the container only ever resolves bare
# container names (e.g. "b2") via LLMNR multicast, never via the real nspawnbr0 DNS server - confirmed
# live via `resolvectl log-level debug` + journalctl: it fired LLMNR transactions only, never even
# attempting the "dns" scope, despite the scope being on the link's default route. This is
# systemd-resolved's own documented behaviour for single-label (undotted) names: they're only routed
# to a configured DNS server if the link has a routing/search domain to qualify them with. DHCP could
# supply this (option 15), but that needs the client-side .network file to opt in with UseDomains=yes
# too, and /usr/lib/systemd/network/80-container-host0.network is systemd-nspawn's own generated file,
# not something this template controls. A static drop-in (merged by filename, like a systemd unit
# drop-in) sidesteps DHCP entirely: Domains=internal marks nspawnbr0's own dnsmasq (10.100.0.1, this
# domain's DNS server per resolvectl status) as authoritative for .internal, including for bare names.
mkdir -p "$ROOTFS/etc/systemd/network/80-container-host0.network.d"
printf '[Network]\nDomains=internal\n' > "$ROOTFS/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf"
# Belt and braces: openssh-server's own postinst already enables the service via
# deb-systemd-helper, but do it explicitly too. systemctl enable doesn't need a running systemd (or
# a bus connection) to manipulate unit-file symlinks, so this is safe to run chrooted with no init
# process behind it.
chroot "$ROOTFS" systemctl enable ssh

# Pack into a machinectl import-tar-compatible gzipped tar - --numeric-owner keeps uid/gid numeric
# in the archive so machinectl doesn't need matching /etc/passwd entries on the importing host to
# make sense of ownership.
mkdir -p "$(dirname "$TARGET")"
tar -czf "$TARGET" --numeric-owner -C "$ROOTFS" .

echo "Debian $DEBIAN_RELEASE minirootfs ready at $TARGET"
