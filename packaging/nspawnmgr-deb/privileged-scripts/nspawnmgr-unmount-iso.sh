#!/bin/sh
# Releases the host-side loop mount nspawnmgr-mount-iso.sh created. Best-effort (`|| true`): safe to
# call even if nothing is currently mounted there (e.g. ejecting a container that was never started
# since its ISO was configured).
#
# $1 = machine name
set -e
MACHINE="$1"
MOUNT_POINT="/var/lib/nspawnmgr/iso-mounts/$MACHINE"

umount "$MOUNT_POINT" 2>/dev/null || true
rmdir "$MOUNT_POINT" 2>/dev/null || true
echo "nspawnmgr-unmount-iso.sh: released $MOUNT_POINT"
