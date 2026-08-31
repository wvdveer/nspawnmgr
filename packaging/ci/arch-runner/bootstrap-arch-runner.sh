#!/bin/sh
# One-off bootstrap for the "arch" Gitea Actions runner: bakes a real Arch Linux rootfs directly
# under /var/lib/machines/arch-runner (a machinectl-discoverable directory-backed container),
# with the base-devel toolchain + a passwordless-sudo "builder" user + a disabled act-runner.service
# unit ready to enable once the runner is registered.
#
# This is CI/build infra for acer (see docs on the "arch:host" Gitea Actions runner label), not a
# template consumed by nspawnmgr's own ContainerFilesystemProvisioner - it's run by hand, once,
# directly on acer. Deliberately adapted from
# packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-arch-template.sh rather than
# reusing it unmodified: that script only installs openssh and always produces a packed .tar.gz
# template; this one installs the base-devel group + git/sudo/ca-certificates and bakes straight
# into a live machinectl directory, since this container is meant to run indefinitely, not be
# imported repeatedly.
#
# Same host-distro assumption as the original: acer is Debian-based (confirmed, no `pacman` on
# the bare host), so this always takes the chroot branch - the host-side pacman `--root=` branch
# from the original script is intentionally NOT carried over here, since it would never be
# exercised on acer and would be unverified dead code.
#
# Run as root (sudo) on acer. No arguments - always targets /var/lib/machines/arch-runner.
set -e

ROOTFS="/var/lib/machines/arch-runner"
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64) ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
esac
INDEX_URL="https://images.linuxcontainers.org/images/archlinux/current/${ARCH}/default/"

if [ -e "$ROOTFS" ]; then
    echo "$ROOTFS already exists - refusing to overwrite. Remove it first if you want a fresh bake." >&2
    exit 1
fi

for cmd in curl tar sha256sum chroot mount umount; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

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

# Same three pacman.conf workarounds as nspawnmgr-create-arch-template.sh - see that script's
# header comment for the full rationale of each (uncommented mirrorlist, SigLevel=Never in place
# of replicating pacman-key --init/--populate, CheckSpace disabled because chroot mountinfo
# parsing breaks it, DisableSandbox because Landlock isn't available under nspawn's seccomp
# filter).
echo "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch" > "$ROOTFS/etc/pacman.d/mirrorlist"
sed -i 's/^SigLevel.*/SigLevel = Never/' "$ROOTFS/etc/pacman.conf"
sed -i 's/^CheckSpace/#CheckSpace/' "$ROOTFS/etc/pacman.conf"
sed -i '/^\[options\]/a DisableSandbox' "$ROOTFS/etc/pacman.conf"

echo "Installing base-devel, git, sudo, ca-certificates into the rootfs..."
cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
chroot "$ROOTFS" pacman --noconfirm -Sy
chroot "$ROOTFS" pacman --noconfirm -S base-devel git sudo ca-certificates
umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

echo "Creating builder user with passwordless sudo..."
chroot "$ROOTFS" useradd -m -s /bin/bash builder
echo 'builder ALL=(ALL) NOPASSWD: ALL' > "$ROOTFS/etc/sudoers.d/builder"
chmod 440 "$ROOTFS/etc/sudoers.d/builder"

echo "Baking (disabled) act-runner.service..."
mkdir -p "$ROOTFS/etc/systemd/system"
cat > "$ROOTFS/etc/systemd/system/act-runner.service" <<'EOF'
[Unit]
Description=Gitea Actions runner (arch:host)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=builder
WorkingDirectory=/state
ExecStart=/usr/local/bin/act_runner daemon
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
# Left disabled deliberately - /state has no .runner file yet until `act_runner register` is run
# by hand post-boot (needs a fresh Gitea registration token, not available at bake time). Enable
# with `machinectl shell arch-runner systemctl enable --now act-runner` once registered.

echo "Arch rootfs baked at $ROOTFS."
echo "Next: create /etc/systemd/nspawn/arch-runner.nspawn with Bind= entries for the host"
echo "act_runner binary and a persistent /state dir, then 'machinectl start arch-runner'."
