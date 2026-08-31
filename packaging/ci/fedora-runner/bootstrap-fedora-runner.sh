#!/bin/sh
# One-off bootstrap for the "fedora" Gitea Actions runner: bakes a real Fedora rootfs directly
# under /var/lib/machines/fedora-runner (a machinectl-discoverable directory-backed container),
# with a passwordless-sudo "builder" user ready for the CI job to install its own build deps at
# runtime (same division of labor as packaging/ci/arch-runner/bootstrap-arch-runner.sh - keep the
# base image minimal, let the job itself pull in JDK/Maven so upgrading either doesn't need a
# re-bake). Exists specifically to get REAL verification of packaging/nspawnmgr-rpm/ (dnf/rpm
# scriptlet behavior, nspawnmgr-bootstrap-app-machine.sh's chroot-based Debian-minimal baking from
# a Fedora bare host, Fedora's own firewalld quirks) - none of that is verifiable without a real
# Fedora host, same reasoning that motivated the Arch runner.
#
# Deliberately adapted from
# packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-fedora-template.sh's Debian-host
# chroot-bake technique (same dnf --releasever=$FEDORA_RELEASE chroot dance, same
# resolv.conf/--remove-destination and /run-bind-mount DNS fixes) rather than reusing it
# unmodified: that script only installs openssh-server and always produces a packed .tar.gz
# template; this one installs a broader base + creates a builder user and bakes straight into a
# live machinectl directory, since this container is meant to run indefinitely, not be imported
# repeatedly. Same host-distro assumption as that script: acer is Debian-based (confirmed, no
# `dnf` on the bare host), so this always takes the chroot branch.
#
# Run as root (sudo) on acer. No arguments - always targets /var/lib/machines/fedora-runner.
set -e

ROOTFS="/var/lib/machines/fedora-runner"
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64) ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
esac
# Same release nspawnmgr-create-fedora-template.sh already settled on (43, not 44) - see that
# script's own comment: not because 44 is broken, just the combination actually confirmed working
# elsewhere in this project, kept for consistency rather than re-verifying 44 unnecessarily here.
FEDORA_RELEASE="43"
INDEX_URL="https://images.linuxcontainers.org/images/fedora/${FEDORA_RELEASE}/${ARCH}/default/"

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

echo "Installing base-devel-equivalent + git, sudo, ca-certificates into the rootfs..."
# Same DNS-resolution fixes nspawnmgr-create-fedora-template.sh's own chroot branch needed - see
# that script's own comments for the full root cause of each (resolv.conf symlink loop,
# nss-resolve needing /run's systemd-resolved socket).
cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
chroot "$ROOTFS" dnf --releasever="$FEDORA_RELEASE" --assumeyes makecache
# @development-tools is Fedora/RHEL's own base-devel-equivalent group (gcc, make, rpm-build,
# rpmdevtools among others) - rpm-build/rpmdevtools aren't strictly needed to build a .rpm via
# Maven's rpm-maven-plugin (Redline-based, pure Java, no real rpmbuild invoked), but they ARE
# useful for inspecting/debugging a built .rpm interactively on this runner, and dnf/rpm
# themselves (needed for the actual install-time verification this runner exists for) are already
# part of the base Fedora image regardless.
chroot "$ROOTFS" dnf --releasever="$FEDORA_RELEASE" --assumeyes group install "development-tools"
chroot "$ROOTFS" dnf --releasever="$FEDORA_RELEASE" --assumeyes install git sudo ca-certificates

# Creating the user INSIDE this same mounted block, before unmounting - confirmed live: Fedora's
# useradd -m (unlike Arch's, which works fine unmounted - see bootstrap-arch-runner.sh) failed
# copying /etc/skel/.bashrc with "Bad file descriptor" once /dev/proc/run/sys were unmounted
# first, silently leaving the account never created at all (chroot's own exit code didn't
# propagate as a script-aborting failure under set -e the way a plain command's would).
echo "Creating builder user with passwordless sudo..."
chroot "$ROOTFS" useradd -m -s /bin/bash builder
echo 'builder ALL=(ALL) NOPASSWD: ALL' > "$ROOTFS/etc/sudoers.d/builder"
chmod 440 "$ROOTFS/etc/sudoers.d/builder"

umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

# Confirmed live: the same broken unix_chkpwd already documented in
# nspawnmgr-create-fedora-template.sh's own comments for sshd/systemd-user (current Fedora
# shadow-utils refuses to run its setuid helper inside a systemd-nspawn container) hits EVERY PAM
# service that checks account validity via pam_unix.so - not just sshd. First discovered via
# `machinectl shell builder@fedora-runner` silently doing nothing (no error, no effect, same
# "Connected... Connection terminated" banner as a real command), traced to /etc/pam.d/login and
# /etc/pam.d/remote (the two services machinectl shell's own login path uses); then discovered
# AGAIN when the CI job's own `sudo` calls (invoked directly by act-runner.service's User=builder,
# not through machinectl shell at all) failed with "sudo: PAM account management error:
# Authentication service cannot retrieve authentication info" - /etc/pam.d/sudo and
# /etc/pam.d/sudo-i, a THIRD and FOURTH affected service file.
#
# Rather than keep patching individual service files one at a time as each new one surfaces (every
# one of the four above just does `account include system-auth` or `password-auth`), fix the
# actual root cause once: modern Fedora's /etc/pam.d/system-auth and /etc/pam.d/password-auth are
# themselves symlinks to /etc/authselect/system-auth and /etc/authselect/password-auth (authselect-
# generated, present even on this minimal dnf --installroot build) - patch the real files there
# instead. Fixes every current AND future service that includes either one (login, remote, sudo,
# sudo-i, systemd-user for RDP, polkit, ...) in one place. GNU sed's `-E` (POSIX ERE) does NOT
# support `\s` as a whitespace shorthand (confirmed live - it silently matched nothing) -
# `[[:space:]]` is the portable equivalent. Captures the existing "account ... required ..."
# prefix exactly as-is rather than trying to reconstruct authselect's own column padding by hand.
sed -i -E 's/^(account[[:space:]]+required[[:space:]]+)pam_unix\.so.*/\1pam_permit.so/' "$ROOTFS/etc/authselect/system-auth"
sed -i -E 's/^(account[[:space:]]+required[[:space:]]+)pam_unix\.so.*/\1pam_permit.so/' "$ROOTFS/etc/authselect/password-auth"

# Confirmed live: DNS resolution for anything beyond localhost/the acer hosts-file entry (e.g. a
# real `dnf install` hitting mirrors.fedoraproject.org) failed outright - `resolvectl status`
# showed "Current Scopes: none" on every link (even the ones shared with the host via
# Private=no), meaning the container's OWN systemd-resolved instance never got real per-link DNS
# server config for them, despite /etc/resolv.conf's stub-resolver symlink looking identical to
# the host's own. Arch's minimal image doesn't run systemd-resolved at all, which is exactly why
# arch-runner never hit this class of problem. Simplest, most robust fix: don't fight
# systemd-resolved's own per-link-scope state for a shared-network container that doesn't need
# split-horizon DNS anyway - disable it outright and use a plain static resolv.conf pointing at
# real upstream servers, same 1.1.1.1/9.9.9.9 convention this project already uses elsewhere for
# containers. `chroot ... systemctl disable` is safe with no running init (same "unit-file symlink
# manipulation only" precedent as the sshd `systemctl enable` call in
# nspawnmgr-create-fedora-template.sh) - no need for `--now`/stopping anything at bake time, since
# nothing is running yet.
chroot "$ROOTFS" systemctl disable systemd-resolved
rm -f "$ROOTFS/etc/resolv.conf"
printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\n' > "$ROOTFS/etc/resolv.conf"

echo "Baking (disabled) act-runner.service..."
mkdir -p "$ROOTFS/etc/systemd/system"
cat > "$ROOTFS/etc/systemd/system/act-runner.service" <<'EOF'
[Unit]
Description=Gitea Actions runner (fedora:host)
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
# by hand post-boot (needs a fresh Gitea registration token, not available at bake time).

echo "Fedora rootfs baked at $ROOTFS."
echo "Next: create /etc/systemd/nspawn/fedora-runner.nspawn with Bind= entries for the host"
echo "act_runner binary and a persistent /state dir (Private=no / PrivateUsers=no, same as"
echo "arch-runner.nspawn - see packaging/ci/arch-runner/ for the exact known-working recipe),"
echo "add 'acer'/'acer.patersonst' to the container's own /etc/hosts (Gitea checkout needs this -"
echo "same DNS gap arch-runner hit), then 'machinectl start fedora-runner'."
