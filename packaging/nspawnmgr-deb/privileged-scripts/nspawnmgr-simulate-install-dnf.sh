#!/bin/sh
# DNF analogue of nspawnmgr-simulate-install.sh: determines which of an uploaded local .rpm's
# dependencies aren't already satisfied on the target container, without installing anything. Run
# via a HOST-side chroot into the container's rootfs - NOT `systemd-run --machine=` (in-container
# execution) - same conversion nspawnmgr-download-packages-dnf.sh's own sibling already got: a real
# provisioning run confirmed downloads/syncs issued from inside a container's own network namespace
# are unreliable even with working host-level network/DNS, and this script's own `dnf makecache`
# call hits the network exactly the same way.
#
# The uploaded .rpm itself lives in the host-side admin package cache, not inside the container's
# own rootfs, so it's copied in to a scratch location first and referenced by its in-rootfs path
# (chroot makes "in-container path" and "path relative to $ROOTFS" the same thing) from there on.
#
# UNVERIFIED beyond the network-reliability fix itself - see nspawnmgr-download-packages-dnf.sh's
# own disclaimer; this script's own dependency-simulation logic was already speculative before this
# conversion and stays exactly as speculative after it.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>) - no longer
#      needs the container's own machinectl/systemd-nspawn name at all.
# $2 = host-visible path to the uploaded .rpm to simulate installing (NOT inside $1 - it lives in
#      the admin package cache)
set -e
ROOTFS="$1"
RPM_PATH="$2"

IN_CONTAINER_SCRATCH=/var/cache/nspawnmgr-simulate
HOST_SCRATCH="$ROOTFS$IN_CONTAINER_SCRATCH"
rm -rf "$HOST_SCRATCH"
mkdir -p "$HOST_SCRATCH"
cp "$RPM_PATH" "$HOST_SCRATCH/"
IN_CONTAINER_RPM="$IN_CONTAINER_SCRATCH/$(basename "$RPM_PATH")"

# Same chroot setup nspawnmgr-download-packages-dnf.sh uses - see that script's own comments for the
# full rationale of each mount/copy.
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

OWN_NAME="$(chroot "$ROOTFS" rpm -qp --queryformat '%{NAME}' "$IN_CONTAINER_RPM")"
chroot "$ROOTFS" dnf --assumeyes makecache >/dev/null
chroot "$ROOTFS" dnf --assumeyes install --assumeno "$IN_CONTAINER_RPM" 2>/dev/null | awk '
    /^Installing/ { active=1; next }
    /^Transaction Summary/ { active=0 }
    active && NF >= 4 && $1 !~ /^=+$/ { print $1 }
' | grep -vFx "$OWN_NAME" || true

umount "$ROOTFS/sys"
umount "$ROOTFS/proc"
umount "$ROOTFS/dev"
umount "$ROOTFS/run"

rm -rf "$HOST_SCRATCH"
