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
# Genuinely more speculative than the DNF analogue even past this conversion: pacman's local-file
# (-U) --print semantics for a full dependency-closure dry run have never been exercised anywhere in
# this project, not even manually - built to the documented pacman(8) contract as carefully as
# possible.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>) - no longer
#      needs the container's own machinectl/systemd-nspawn name at all.
# $2 = host-visible path to the uploaded .pkg.tar.* to simulate installing (NOT inside $1 - it lives
#      in the admin package cache)
set -e
ROOTFS="$1"
PKG_PATH="$2"

IN_CONTAINER_SCRATCH=/var/cache/nspawnmgr-simulate
HOST_SCRATCH="$ROOTFS$IN_CONTAINER_SCRATCH"
rm -rf "$HOST_SCRATCH"
mkdir -p "$HOST_SCRATCH"
cp "$PKG_PATH" "$HOST_SCRATCH/"
IN_CONTAINER_PKG="$IN_CONTAINER_SCRATCH/$(basename "$PKG_PATH")"

# Same chroot setup nspawnmgr-download-packages-pacman.sh uses - see that script's own comments for
# the full rationale of each mount/copy.
cleanup() {
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

OWN_NAME="$(chroot "$ROOTFS" pacman -Qip "$IN_CONTAINER_PKG" | awk -F': *' '/^Name/ {print $2; exit}')"
chroot "$ROOTFS" pacman --noconfirm -Sy >/dev/null
chroot "$ROOTFS" pacman --noconfirm -U --print --print-format '%n' "$IN_CONTAINER_PKG" 2>/dev/null | grep -vFx "$OWN_NAME" || true

umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

rm -rf "$HOST_SCRATCH"
