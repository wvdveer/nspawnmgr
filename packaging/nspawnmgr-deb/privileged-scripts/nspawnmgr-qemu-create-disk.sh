#!/bin/sh
# Creates a new, empty qcow2 disk for a from-scratch QEMU VM - the one QEMU creation step gated on
# a sudo password (PASSWORD tier, matching nspawnmgr-clone-template.sh/
# nspawnmgr-podman-create-container.sh's own "new persistent artifact" tier). Actually launching the
# VM is a separate, NOPASSWD step (see nspawnmgr-qemu-write-unit.sh + plain `systemctl start` -
# ContainerCliExecutor.start's QEMU branch never needs a password, matching podman start/machinectl
# start both being NOPASSWD despite create/clone needing one).
# $1 = VM name, $2 = disk size in whole GB.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-create-disk.sh must be run as root." >&2
    exit 1
fi
if ! command -v qemu-img >/dev/null 2>&1; then
    echo "qemu-img is not installed - see the Diagnostics page." >&2
    exit 1
fi

name="$1"
size_gb="$2"
disk_dir="/var/lib/nspawnmgr/qemu-disks"
disk_path="$disk_dir/$name.qcow2"

if [ -e "$disk_path" ]; then
    echo "A disk already exists for '$name' at $disk_path." >&2
    exit 1
fi

mkdir -p "$disk_dir"
qemu-img create -f qcow2 "$disk_path" "${size_gb}G"
