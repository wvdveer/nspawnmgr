#!/bin/sh
# Clones a QEMU template (a plain qcow2 file) into a new VM's disk - the QEMU equivalent of
# nspawnmgr-clone-template.sh (which uses `machinectl import-tar`). No tar/conversion needed, just
# a plain copy into the fixed /var/lib/nspawnmgr/qemu-disks/<name>.qcow2 convention (same one
# nspawnmgr-qemu-create-disk.sh uses for a from-scratch disk).
# $1 = source .qcow2 template path, $2 = destination VM name.
# Requires a sudo password (see ContainerFilesystemProvisioner.cloneQemuTemplate) — creation-time only.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-clone-template.sh must be run as root." >&2
    exit 1
fi

source="$1"
name="$2"
disk_dir="/var/lib/nspawnmgr/qemu-disks"
disk_path="$disk_dir/$name.qcow2"

if [ -e "$disk_path" ]; then
    echo "A disk already exists for '$name' at $disk_path." >&2
    exit 1
fi
if [ ! -f "$source" ]; then
    echo "No template found at $source" >&2
    exit 1
fi

mkdir -p "$disk_dir"
cp "$source" "$disk_path"
