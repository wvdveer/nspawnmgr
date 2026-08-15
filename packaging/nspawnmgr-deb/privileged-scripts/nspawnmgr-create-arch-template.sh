#!/bin/sh
# Downloads an Arch Linux minirootfs from images.linuxcontainers.org, extracts it, and bakes
# openssh into it.
#
# If the HOST itself is Arch (has pacman), installs it as a normal host process pointed at
# --root=, mirroring nspawnmgr-create-debian-template.sh's own host-side apt branch. Otherwise
# (nspawnmgr is deployed on some other distro entirely - Debian, Fedora, ...), falls back to
# chrooting into the rootfs and using ITS OWN bundled pacman instead - the downloaded image already
# carries its own copy, so there's nothing to install on the host at all, the same technique
# pacstrap/arch-chroot themselves use (this script is effectively a hand-rolled, minimal pacstrap).
# Confirmed live: the host running nspawnmgr is typically Debian/Ubuntu-based, where the host-side
# branch alone 404'd immediately with "Required command 'pacman' not found." - the chroot fallback
# exists specifically for that case. UNVERIFIED either way beyond that: no Arch host exists anywhere
# in this project's test environment (unlike nspawnmgr-create-debian-template.sh's own host-side
# branch, confirmed live multiple times), so neither branch here has actually been exercised against
# a real container. Still the most speculative of nspawnmgr's three "set up a minimal template"
# scripts. Two other known risks, called out inline below:
#   - the image's default /etc/pacman.d/mirrorlist ships with every mirror commented out (Arch's
#     own deliberate "you must choose one" convention) - a mirror is written in explicitly below.
#   - real pacstrap sets up a package-signature keyring automatically via pacman-key
#     --init/--populate; this script disables signature checking for the bootstrap install instead
#     of attempting to replicate that dance blind. That's a real security trade-off, acceptable for
#     a quick-start dev/test template but worth knowing about.
#
# $1 = target .tar.gz path (must not already exist) - a machinectl import-tar-compatible gzipped
# tar, not a live directory.
#
# Requires a sudo password (see ContainerFilesystemProvisioner.createMinimalTemplate) —
# creation-time-only, invoked from the Templates admin page's "Set up arch-minimal" button.
set -e

TARGET="$1"
ARCH="$(uname -m)"
# images.linuxcontainers.org names architectures its own way (amd64/arm64), not uname -m's
# (x86_64/aarch64) - same translation nspawnmgr-create-debian-template.sh already needs. Confirmed
# live: without this, every request 404'd even though "current" itself resolved fine.
case "$ARCH" in
    x86_64) ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
esac
INDEX_URL="https://images.linuxcontainers.org/images/archlinux/current/${ARCH}/default/"

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

# Same "resolve the newest build actually published" reasoning as
# nspawnmgr-create-debian-template.sh: images.linuxcontainers.org prunes older timestamped builds
# over time, so pinning one would need periodic bumping to keep working.
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
# on each: if the script fails before a given mount was even set up, that umount is a harmless
# no-op, not a real error - this is a best-effort safety net for the failure path, distinct from
# the explicit, non-`|| true` unmounts on the success path below.
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

mkdir -p "$ROOTFS"
tar -xpf "$WORK_DIR/rootfs.tar.xz" -C "$ROOTFS"

# Belt-and-braces baseline device nodes for the FINAL packed template (systemd-nspawn sets up its
# own private /dev at container boot regardless of what's baked in here, but keep this for
# consistency with nspawnmgr-create-debian-template.sh, and in case anything other than
# systemd-nspawn ever imports this rootfs). Not load-bearing for pacman itself either way - the
# host-side branch below uses --root= (full access to the host's own real /dev), and the chroot
# branch gets a real, fully populated /dev via its own bind mount.
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
mknod -m 666 "$ROOTFS/dev/null" c 1 3
mknod -m 666 "$ROOTFS/dev/zero" c 1 5
mknod -m 666 "$ROOTFS/dev/random" c 1 8
mknod -m 666 "$ROOTFS/dev/urandom" c 1 9
mknod -m 666 "$ROOTFS/dev/tty" c 5 0

# Arch's own mirrorlist convention ships every entry commented out - a fresh install genuinely
# doesn't work until an admin (or, here, this script) picks one. geo.mirror.pkgbuild.com is Arch's
# own official GeoIP-based mirror redirector, the same one the real installer/pacstrap defaults
# point at. This (and the SigLevel edit below) stay UNCONDITIONAL, ahead of the host-vs-chroot
# branch below - both the host-side (--root=) and chroot pacman invocations read these same
# $ROOTFS files, so there's no separate "per-branch" version of either edit.
echo "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch" > "$ROOTFS/etc/pacman.d/mirrorlist"

# SigLevel = Never: see the file-level comment above. Real pacstrap-built systems keep signature
# checking on via pacman-key --init/--populate; skipping that setup here trades it away rather
# than risk getting the keyring dance wrong with no way to test it. sed edits pacman.conf in place
# rather than overwriting it, so every other default in the shipped config is preserved.
sed -i 's/^SigLevel.*/SigLevel = Never/' "$ROOTFS/etc/pacman.conf"

# CheckSpace disabled: pacman's default disk-space check resolves the cache directory to a mount
# point by parsing /proc/self/mountinfo. Inside a chroot (not a fresh mount namespace), that table
# still lists the host's own absolute paths, not the chroot's remapped view of "/" - pacman can't
# match /var/cache/pacman/pkg against anything in it and aborts with "could not determine cachedir
# mount point ... not enough free disk space", regardless of how much space is actually free. A
# known pacman-in-chroot limitation, not specific to this script; disabling the check is the
# standard workaround.
sed -i 's/^CheckSpace/#CheckSpace/' "$ROOTFS/etc/pacman.conf"

# DisableSandbox: confirmed live, a real (booted, running) container created from this template
# failed a later `pacman -Sy` (run live inside the container by ProvisioningService, not chrooted
# during this bake) with "restricting filesystem access failed because Landlock is not supported by
# the kernel!" / "switching to sandbox user 'alpm' failed!". Recent pacman versions sandbox their
# own download step via Landlock LSM + a dedicated unprivileged "alpm" user by default. This baking
# script's own chroot install works fine (a plain host-side chroot has no seccomp restrictions at
# all), but systemd-nspawn applies its own default seccomp filter to every container it actually
# boots, which blocks the Landlock syscalls pacman's sandboxing needs - not something baking itself
# can detect, only a real booted container hits it. This file is the one baked into the final
# template, so this fix applies to every pacman invocation that will ever run inside a container
# made from it, not just this script's own chroot step. Appended (not sed-substituted): unlike
# SigLevel/CheckSpace, the stock pacman.conf has no commented-out DisableSandbox line to toggle -
# duplicate lines are fine, pacman uses the last one it parses.
sed -i '/^\[options\]/a DisableSandbox' "$ROOTFS/etc/pacman.conf"

echo "Installing openssh into the template..."
if command -v pacman >/dev/null 2>&1; then
    # Host is Arch - run pacman as a normal HOST process against --root=, same rationale as the
    # Debian script's own host-side apt branch: uses the host's own working network/DNS instead of
    # relying on in-container network setup. --root= makes pacman treat $ROOTFS as / for both its
    # database and its target filesystem, reading the mirrorlist/pacman.conf edits made above.
    pacman --root="$ROOTFS" --noconfirm -Sy
    pacman --root="$ROOTFS" --noconfirm -S openssh
else
    # Host has no pacman at all (nspawnmgr deployed on a Debian or Fedora host, say) - chroot into
    # the rootfs and use ITS OWN bundled pacman instead (using the image's OWN bundled pacman, the
    # same technique pacstrap/arch-chroot themselves use). chroot doesn't share the host's network
    # config automatically - pacman needs $ROOTFS's own resolv.conf to resolve Arch's mirrors.
    # Copying (rather than bind-mounting) follows the symlink if /etc/resolv.conf is one (e.g.
    # systemd-resolved's stub-resolver setup), and this is a one-shot bake, not a persistent mount,
    # so no ongoing sync is needed afterwards. /proc and /sys get their own fresh mounts (not bind
    # mounts of the host's real ones) - pacman's own hooks and some packages' post-install
    # scriptlets expect them to exist, the same reason pacstrap/arch-chroot set these up before
    # running pacman. These three unmount again right after the install, deliberately NOT left
    # mounted until the trap-based cleanup at script exit - packing the rootfs into $TARGET further
    # down must never capture a live snapshot of the host's own /proc or /dev. --root= is gone
    # here: we're not pointing an external pacman at $ROOTFS anymore, we ARE chrooted into it, so
    # plain pacman commands apply to $ROOTFS as if it were /.
    # --remove-destination: some minimal base images ship /etc/resolv.conf as a symlink to the
    # absolute path "/etc/resolv.conf" itself (a placeholder meant to be replaced by whatever
    # container runtime boots it, confirmed live for the Fedora image) - outside a chroot that
    # resolves right back to the HOST's own real /etc/resolv.conf, so a plain `cp` sees source and
    # destination as the same inode and refuses with "are the same file". --remove-destination
    # unlinks the destination path first, so cp never has to open-and-follow that symlink at all.
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    # /run bind mount: confirmed live, without this pacman failed DNS resolution entirely
    # ("Could not resolve host: geo.mirror.pkgbuild.com") despite the resolv.conf copy above being
    # correct. Arch's own default /etc/nsswitch.conf lists "resolve" ahead of "dns" - nss-resolve
    # doesn't read /etc/resolv.conf at all, it talks to systemd-resolved over the socket it publishes
    # under /run. Since the chroot's own /run was untouched (freshly extracted, essentially empty),
    # that socket was never reachable - which apparently doesn't cleanly fall through to plain "dns"
    # resolution in every case, even though nsswitch's "[!UNAVAIL=return]" qualifier suggests it
    # should. Real `arch-chroot`/`pacstrap` bind-mount /run for exactly this reason; do the same here.
    mkdir -p "$ROOTFS/run"
    mount --bind /run "$ROOTFS/run"
    mount --bind /dev "$ROOTFS/dev"
    mount -t proc proc "$ROOTFS/proc"
    mount -t sysfs sys "$ROOTFS/sys"
    chroot "$ROOTFS" pacman --noconfirm -Sy
    chroot "$ROOTFS" pacman --noconfirm -S openssh
    umount "$ROOTFS/sys"
    umount "$ROOTFS/proc"
    umount "$ROOTFS/dev"
    umount "$ROOTFS/run"
fi

# Same two systemd-nspawn-level workarounds nspawnmgr-create-debian-template.sh needed, confirmed
# live for Debian - these are about systemd-networkd/systemd-nspawn's own generated network config,
# not anything Debian-specific, so the same fix should apply to any systemd-based rootfs (Arch's own
# base package includes systemd). UNVERIFIED for Arch specifically though - flag if a live Arch
# container still can't ping or resolve other containers by name despite this.
mkdir -p "$ROOTFS/etc/sysctl.d"
echo "net.ipv4.ping_group_range = 0 65535" > "$ROOTFS/etc/sysctl.d/50-ping-group-range.conf"
mkdir -p "$ROOTFS/etc/systemd/network/80-container-host0.network.d"
printf '[Network]\nDomains=internal\n' > "$ROOTFS/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf"

# Arch's OpenSSH service unit is sshd.service (its package is "openssh", not "openssh-server" -
# Arch doesn't split client/server the way Debian does). systemctl enable doesn't need a running
# systemd (or a bus connection) to manipulate unit-file symlinks, so this is safe to run chrooted
# with no init process behind it - confirmed live for the same call in the Debian script, after
# dev/proc/sys were already unmounted.
chroot "$ROOTFS" systemctl enable sshd

# Same systemd 257+ OSC-context fix nspawnmgr-create-fedora-template.sh needs - not
# Fedora-specific, Arch's own systemd package is just as current. See that script's own comment
# for the full root cause (Guacamole's terminal doesn't strip the OSC 3008 "Hierarchical Context
# Signalling" escape sequence /usr/lib/systemd/profile.d/80-systemd-osc-context.sh emits on every
# prompt) and the exact disable procedure, taken from the script's own header comment.
if [ -h "$ROOTFS/etc/profile.d/80-systemd-osc-context.sh" ]; then
    rm -f "$ROOTFS/etc/profile.d/80-systemd-osc-context.sh"
    ln -sf /dev/null "$ROOTFS/etc/tmpfiles.d/20-systemd-osc-context.conf"
fi

mkdir -p "$(dirname "$TARGET")"
tar -czf "$TARGET" --numeric-owner -C "$ROOTFS" .

echo "Arch Linux minirootfs ready at $TARGET"
