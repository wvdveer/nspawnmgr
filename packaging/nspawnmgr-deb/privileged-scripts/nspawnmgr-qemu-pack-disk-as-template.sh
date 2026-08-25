#!/bin/sh
# Packs a QEMU VM's current qcow2 disk into a new template file - the QEMU equivalent of
# nspawnmgr-pack-machine-as-template.sh (which tars a rootfs). No tar/conversion needed since a
# template is just a qcow2 file too - a plain copy. The caller is responsible for only invoking
# this while the VM is STOPPED (copying a live disk risks an inconsistent image as the guest
# writes to it) - this script itself doesn't check.
#
# $1 = VM name (disk resolved at the fixed /var/lib/nspawnmgr/qemu-disks/<name>.qcow2 convention,
# same as nspawnmgr-qemu-create-disk.sh)
# $2 = target .qcow2 path (must not already exist)
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-pack-disk-as-template.sh must be run as root." >&2
    exit 1
fi

name="$1"
target="$2"
disk_path="/var/lib/nspawnmgr/qemu-disks/$name.qcow2"

if [ -e "$target" ]; then
    echo "Target file already exists" >&2
    exit 1
fi
if [ ! -f "$disk_path" ]; then
    echo "No disk found at $disk_path" >&2
    exit 1
fi

mkdir -p "$(dirname "$target")"
cp "$disk_path" "$target"
