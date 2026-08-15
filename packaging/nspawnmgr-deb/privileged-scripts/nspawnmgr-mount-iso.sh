#!/bin/sh
# Loop-mounts an ISO file on the host at this container's fixed mount point. Purely a host-side
# filesystem operation - getting it into the container itself is the static [Files] BindReadOnly=
# line NspawnSettingsRenderer writes into the .nspawn settings file (see that class's own comment
# for why this is a static setting, not a live `machinectl bind`, and takes effect on next start
# like every other .nspawn setting).
#
# $1 = machine name
# $2 = ISO file's host-visible path (e.g. /var/cache/nspawnmgr/packages/iso/uploaded/<stored-filename>)
set -e
MACHINE="$1"
ISO_PATH="$2"
MOUNT_POINT="/var/lib/nspawnmgr/iso-mounts/$MACHINE"

mkdir -p "$MOUNT_POINT"
mount -o loop,ro "$ISO_PATH" "$MOUNT_POINT"
echo "nspawnmgr-mount-iso.sh: loop-mounted $ISO_PATH -> $MOUNT_POINT"
