#!/bin/sh
# Layers PostgreSQL on top of an already-baked debian-minimal image, producing a second template
# for the "bundled" .deb variant's DB-machine offline path - see vendor/README.md's own
# "postgresql-minimal.tar.gz" section. Only PostgreSQL gets this treatment (not MySQL/MariaDB, per
# explicit user decision): it's the one engine choice that gives an admin doing a fully offline
# install *something* usable without a network-connected DB-provisioning step, not full parity
# across every engine.
#
# $1 = source debian-minimal.tar.gz (already baked - see nspawnmgr-create-debian-template.sh)
# $2 = target .tar.gz path (must not already exist)
#
# Same host-apt-pointed-at-a-foreign-root / chroot-fallback technique every other bake script here
# uses - see nspawnmgr-create-debian-template.sh's own comments for the full reasoning (DNS-from-
# inside-a-container unreliability, apt's --root= flag being unusable on some hosts, the
# APT::Sandbox::User=root/DPkg::Options::=--root= combo, etc.), not repeated here.
#
# Run this on a Debian/Ubuntu-family host with real internet access - same reasoning as
# debian-minimal.tar.gz's own vendor/README.md instructions.
set -e

SOURCE="$1"
TARGET="$2"

if [ -z "$SOURCE" ] || [ -z "$TARGET" ]; then
    echo "Usage: nspawnmgr-create-postgresql-template.sh <source-debian-minimal.tar.gz> <target.tar.gz>" >&2
    exit 1
fi
if [ ! -e "$SOURCE" ]; then
    echo "Source image not found: $SOURCE" >&2
    exit 1
fi
if [ -e "$TARGET" ]; then
    echo "Target file already exists" >&2
    exit 1
fi

for cmd in tar chroot mount umount; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

WORK_DIR="$(mktemp -d)"
ROOTFS="$WORK_DIR/rootfs"
cleanup() {
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

mkdir -p "$ROOTFS"
tar -xpf "$SOURCE" -C "$ROOTFS"

export DEBIAN_FRONTEND=noninteractive
echo "Installing postgresql into the template..."
# /proc/dev/sys/run bind-mounted unconditionally, in BOTH branches below - confirmed live
# (nspawnmgr-bootstrap-app-machine.sh/-db-machine.sh's own JRE/engine installs) that a package's
# postinst can need a real /proc even in the host-apt branch (Postgres's own initdb-on-first-
# install step is exactly the kind of script likely to hit this).
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
if command -v apt-get >/dev/null 2>&1; then
    APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$ROOTFS"
    apt-get $APT_OPTS update
    apt-get $APT_OPTS install -y postgresql
else
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    chroot "$ROOTFS" apt-get update
    chroot "$ROOTFS" apt-get install -y postgresql
fi
umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

# Pack into a machinectl import-tar-compatible gzipped tar, same convention as
# nspawnmgr-create-debian-template.sh's own final step.
mkdir -p "$(dirname "$TARGET")"
tar -czf "$TARGET" --numeric-owner -C "$ROOTFS" .

echo "PostgreSQL-preinstalled template ready at $TARGET"
